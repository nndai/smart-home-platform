package com.nndai.myhome.data.pairing

import android.content.Context
import android.util.Log
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.remote.SupabaseConfig
import com.nndai.myhome.data.repository.DeviceManagerRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.MqttCredential
import com.nndai.myhome.data.repository.CredentialSyncResult

data class WifiNetworkInfo(
    val name: String,
    val rssi: Int,
    val encrypted: Boolean,
    val bssid: String = ""
)

sealed interface PairingState {
    data object Idle : PairingState
    data class ScanningDevices(val devices: List<PairingDevice>) : PairingState
    data class ConnectingAp(val device: PairingDevice) : PairingState
    data class DeviceReady(val deviceId: String, val profile: String, val apSsid: String) : PairingState
    data class ScanningWifi(val deviceId: String, val profile: String) : PairingState
    data class WaitingForReconnect(val deviceId: String, val profile: String) : PairingState
    data class WifiList(
        val deviceId: String,
        val profile: String,
        val networks: List<WifiNetworkInfo>
    ) : PairingState
    data object SendingPair : PairingState
    data class Claiming(
        val deviceId: String,
        val profile: String,
        val controlKeyHex: String
    ) : PairingState
    data class Claimed(
        val deviceId: String,
        val profile: String,
        val controlKeyHex: String
    ) : PairingState
    data class ClaimSavedOffline(
        val deviceId: String,
        val profile: String,
        val controlKeyHex: String
    ) : PairingState
    data class ClaimFailed(
        val deviceId: String,
        val profile: String,
        val controlKeyHex: String,
        val message: String
    ) : PairingState
    data class Failed(val message: String) : PairingState
}

sealed interface ClaimResult {
    data object Success : ClaimResult
    data object SavedOffline : ClaimResult
    data object NeedsLogin : ClaimResult
    data class Error(val message: String) : ClaimResult
}

class PairingRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val scanner = WifiDeviceScanner(context)
    private val connector = DeviceApConnector(context)
    private val keyStore = ControlKeyStore(context)
    private val deviceManager = DeviceManagerRepository(context)

    val scanDevices: StateFlow<List<PairingDevice>> = scanner.devices
    val scanInProgress: StateFlow<Boolean> = scanner.scanning

    /** True while a device-side WiFi scan round-trip is running (≤30s). */
    private val _wifiScanInProgress = MutableStateFlow(false)
    val wifiScanInProgress: StateFlow<Boolean> = _wifiScanInProgress.asStateFlow()

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var ws: FirmwareWsClient? = null
    private var lastGateway: String? = null

    // Credential MQTT shared (HiveMQ) — fetch từ Supabase RPC / Android Keystore khi còn mạng.
    @Volatile
    private var mqttCredential: MqttCredential? = null

    private fun setState(next: PairingState) {
        Log.d(TAG, "STATE: ${_state.value::class.simpleName} -> ${next::class.simpleName} ($next)")
        _state.value = next
    }

    init {
        scope.launch {
            mqttCredential = fetchMqttCredential()
            Log.d(TAG, "init: mqttCredential pre-fetched = ${mqttCredential != null}")
        }
        scope.launch {
            scanner.devices.collect { devices ->
                if (_state.value is PairingState.ScanningDevices) {
                    _state.value = PairingState.ScanningDevices(devices)
                }
            }
        }
    }

    fun startScan() {
        Log.d(TAG, "startScan()")
        setState(PairingState.ScanningDevices(scanner.devices.value))
        scanner.startScan()
        if (mqttCredential == null) {
            scope.launch {
                mqttCredential = fetchMqttCredential()
                Log.d(TAG, "startScan(): mqttCredential fetched = ${mqttCredential != null}")
            }
        }
    }

    /**
     * Lấy credential MQTT shared từ MqttCredentialRepository (Keystore / Supabase RPC).
     */
    private suspend fun fetchMqttCredential(): MqttCredential? {
        val repo = PumpRepositoryProvider.provideCredentialRepository()
        val cached = repo.getCachedCredential()
        if (cached != null && cached.isValid()) {
            // Instant return from Keystore, trigger background sync to check if remote changed
            scope.launch { repo.syncWithRemote() }
            return cached
        }

        return when (val result = repo.syncWithRemote()) {
            is CredentialSyncResult.Updated -> result.newCredential
            is CredentialSyncResult.Unchanged -> result.credential
            is CredentialSyncResult.Failed -> null
        }
    }

    fun connectSystemChooser() {
        Log.d(TAG, "connectSystemChooser()")
        setState(PairingState.ConnectingAp(PairingDevice("device", "myhome-*", 0, "")))
        connector.connectToAnyDeviceAp(
            prefix = "myhome-",
            password = DEVICE_AP_PASSWORD,
            onConnected = { gateway ->
                Log.d(TAG, "connectSystemChooser: AP connected, gateway=$gateway — starting WS connect")
                lastGateway = gateway
                connectWsWithUnknownDevice(gateway)
            },
            onFailed = { message ->
                Log.e(TAG, "connectSystemChooser: AP connect failed: $message")
                setState(PairingState.Failed(message))
            }
        )
    }

    private fun connectWsWithUnknownDevice(gateway: String) {
        scope.launch {
            Log.d(TAG, "connectWsWithUnknownDevice(): gateway=$gateway")
            val wsClient = connectWsWithRetry(gateway)
            if (wsClient == null) {
                connector.disconnect()
                setState(PairingState.Failed("Không kết nối được tới thiết bị"))
                return@launch
            }
            ws = wsClient
            val config = wsClient.request("getConfig")
            Log.d(TAG, "connectWsWithUnknownDevice(): getConfig response = $config")
            val status = config?.get("status")?.jsonPrimitive?.content
            if (config == null || status != "ok") {
                wsClient.close()
                connector.disconnect()
                setState(PairingState.Failed("Không đọc được cấu hình thiết bị"))
                return@launch
            }
            val deviceId = config["deviceId"]?.jsonPrimitive?.content ?: ""
            val profile = config["profile"]?.jsonPrimitive?.content ?: "pump"
            if (deviceId.isBlank()) {
                wsClient.close()
                connector.disconnect()
                setState(PairingState.Failed("Thiết bị không trả về deviceId"))
                return@launch
            }
            setState(PairingState.DeviceReady(deviceId, profile, "myhome-$profile"))
        }
    }

    fun selectDevice(device: PairingDevice) {
        Log.d(TAG, "selectDevice(): ssid=${device.ssid} model=${device.model} bssid=${device.bssid} rssi=${device.rssi}")
        setState(PairingState.ConnectingAp(device))
        connector.connectToDeviceAp(
            ssid = device.ssid,
            password = DEVICE_AP_PASSWORD,
            onConnected = { gateway ->
                Log.d(TAG, "selectDevice: AP connected, gateway=$gateway — starting WS connect")
                lastGateway = gateway
                connectWs(gateway, device)
            },
            onFailed = { message ->
                Log.e(TAG, "selectDevice: AP connect failed: $message")
                setState(PairingState.Failed(message))
            }
        )
    }

    private fun connectWs(gateway: String, device: PairingDevice) {
        scope.launch {
            Log.d(TAG, "connectWs(): gateway=$gateway — retrying up to 5 times")
            val wsClient = connectWsWithRetry(gateway)
            if (wsClient == null) {
                Log.e(TAG, "connectWs(): all retries exhausted for $gateway")
                connector.disconnect()
                setState(PairingState.Failed("Không kết nối được tới thiết bị"))
                return@launch
            }
            ws = wsClient
            Log.d(TAG, "connectWs(): WS connected! sending getConfig")
            val config = wsClient.request("getConfig")
            Log.d(TAG, "connectWs(): getConfig response = $config")
            val status = config?.get("status")?.jsonPrimitive?.content
            if (config == null || status != "ok") {
                Log.e(TAG, "connectWs(): getConfig bad response, closing")
                wsClient.close()
                connector.disconnect()
                setState(PairingState.Failed("Không đọc được cấu hình thiết bị"))
                return@launch
            }
            val deviceId = config["deviceId"]?.jsonPrimitive?.content ?: ""
            val profile = config["profile"]?.jsonPrimitive?.content ?: device.model
            Log.d(TAG, "connectWs(): deviceId=$deviceId profile=$profile")
            if (deviceId.isBlank()) {
                Log.e(TAG, "connectWs(): deviceId blank in response")
                wsClient.close()
                connector.disconnect()
                setState(PairingState.Failed("Thiết bị không trả về deviceId"))
                return@launch
            }
            setState(PairingState.DeviceReady(deviceId, profile, device.ssid))
        }
    }

    private suspend fun connectWsWithRetry(
        gateway: String,
        attempts: Int = 5,
        stepMs: Long = 1000
    ): FirmwareWsClient? {
        repeat(attempts) { attempt ->
            Log.d(TAG, "connectWsWithRetry(): attempt ${attempt + 1}/$attempts to $gateway:82")
            val client = FirmwareWsClient(gateway)
            if (client.connect()) {
                Log.d(TAG, "connectWsWithRetry(): attempt ${attempt + 1} SUCCESS")
                return client
            }
            client.close()
            Log.w(TAG, "connectWsWithRetry(): attempt ${attempt + 1} failed, waiting ${stepMs}ms")
            delay(stepMs)
        }
        return null
    }

    fun scanWifiOnDevice() {
        val current = when (val s = _state.value) {
            is PairingState.DeviceReady -> s
            is PairingState.WifiList -> PairingState.DeviceReady(s.deviceId, s.profile, "")
            else -> return
        }
        Log.d(TAG, "scanWifiOnDevice(): device=${current.deviceId}")
        setState(PairingState.ScanningWifi(current.deviceId, current.profile))
        _wifiScanInProgress.value = true
        scope.launch {
            val networks = scanWifiSync(current)
            _wifiScanInProgress.value = false
            // Quét fail/hết giờ → vẫn vào WifiList (danh sách rỗng):
            // form nhập SSID/pass thủ công luôn khả dụng, không chết flow.
            Log.d(TAG, "scanWifiOnDevice(): got ${networks?.size ?: -1} networks")
            setState(PairingState.WifiList(current.deviceId, current.profile, networks.orEmpty()))
        }
    }

    /**
     * Runs a full WiFi scan on the device and waits for the result.
     *
     * Two device behaviours:
     * - STA-friendly (ESP32/ESP8266): the AP stays up during the scan,
     *   the device replies with a 'completed' event — we just wait for it.
     * - AP-drop (LN882H): the AP radio is shared, so it goes down during
     *   the scan and our socket dies. The device reports `wifiDrop:true`
     *   in the scanWifi response; we then wait for the AP to come back,
     *   reconnect the phone + WebSocket, and poll getScanWifiData.
     */
    private suspend fun scanWifiSync(current: PairingState.DeviceReady): List<WifiNetworkInfo>? {
        val wsClient = ws ?: return null
        val start = wsClient.request("scanWifi")
        if (start == null || start["status"]?.jsonPrimitive?.content != "ok") {
            Log.e(TAG, "scanWifiSync(): scanWifi cmd response: $start")
            return null
        }
        val wifiDrop = start["wifiDrop"]?.jsonPrimitive?.booleanOrNull ?: false
        Log.d(TAG, "scanWifiSync(): scan started, wifiDrop=$wifiDrop")
        if (wifiDrop) {
            return waitForScanDataAfterReconnect(current)
        }

        // AP stays alive: wait for the device to finish and report via event.
        val completed = CompletableDeferred<List<WifiNetworkInfo>>()
        wsClient.setEventHandler { doc ->
            val cmd = doc["cmd"]?.jsonPrimitive?.content
            val status = doc["status"]?.jsonPrimitive?.content
            Log.d(TAG, "scanWifiSync(): event: $doc")

            if (doc.containsKey("networks")) {
                val parsed = parseNetworks(doc)
                if (parsed != null) {
                    Log.d(TAG, "scanWifiSync(): got networks directly from event (${parsed.size} nets)")
                    completed.complete(parsed)
                    return@setEventHandler
                }
            }

            if (cmd == "scanWifi" && status == "completed") {
                Log.d(TAG, "scanWifiSync(): device sent 'completed' event, requesting getScanWifiData")
                scope.launch {
                    val data = wsClient.request("getScanWifiData")
                    val parsed = data?.let { parseNetworks(it) } ?: emptyList()
                    completed.complete(parsed)
                }
            }
        }
        val result = try {
            withTimeout(30_000) { completed.await() }
        } catch (e: Exception) {
            Log.e(TAG, "scanWifiSync(): timed out waiting 'completed' — ${e.message}")
            null
        }
        return result
    }

    /**
     * LN882H path: the AP dropped during the scan, so the phone lost WiFi.
     * Loop until the AP is back + we can read the scan result, with a
     * generous deadline (the device needs a few seconds to restore the AP).
     */
    private suspend fun waitForScanDataAfterReconnect(
        current: PairingState.DeviceReady
    ): List<WifiNetworkInfo>? {
        setState(PairingState.WaitingForReconnect(current.deviceId, current.profile))
        ws?.close()
        ws = null
        val ssid = current.apSsid
        // Scan timeout: 30s (AP reconnect + WebSocket + poll results)
        val deadline = System.currentTimeMillis() + 30_000
        var gateway = lastGateway
        var apRequests = 0
        var lastWsTry = 0L
        var lastApTry = 0L
        while (System.currentTimeMillis() < deadline) {
            val client = ws
            if (client != null) {
                val data = client.request("getScanWifiData", timeoutMs = 4_000)
                val networks = data?.let { parseNetworks(it) }
                if (networks != null) {
                    Log.d(TAG, "waitForScanDataAfterReconnect(): got ${networks.size} networks")
                    return networks
                }
                Log.d(TAG, "waitForScanDataAfterReconnect(): scan not ready yet: $data")
                delay(1_000)
                continue
            }

            val now = System.currentTimeMillis()
            // 1) The phone usually re-joins the AP by itself — try the socket
            //    against the last known gateway before bothering the user.
            if (gateway != null && now - lastWsTry >= 3_000) {
                lastWsTry = now
                val c = connectWsWithRetry(gateway, attempts = 1, stepMs = 0)
                if (c != null) {
                    ws = c
                    continue
                }
            }
            // 2) Socket still dead: ask the phone to (re)join the AP
            //    (spaced out, and only while the device might still answer).
            if (gateway != null && apRequests < 3 && now - lastApTry >= 10_000) {
                lastApTry = now
                apRequests++
                Log.d(TAG, "waitForScanDataAfterReconnect(): AP re-request #$apRequests for '$ssid'")
                val reconnected = withTimeoutOrNull(12_000) {
                    suspendCancellableCoroutine<String?> { cont ->
                        connector.connectToDeviceAp(
                            ssid = ssid,
                            password = DEVICE_AP_PASSWORD,
                            onConnected = { g ->
                                if (cont.isActive) cont.resume(g) { }
                            },
                            onFailed = {
                                if (cont.isActive) cont.resume(null) { }
                            }
                        )
                    }
                }
                if (reconnected != null) {
                    Log.d(TAG, "waitForScanDataAfterReconnect(): AP '$ssid' reconnected, gateway=$reconnected")
                    gateway = reconnected
                }
            }
            delay(500)
        }
        Log.e(TAG, "waitForScanDataAfterReconnect(): timed out waiting for scan data")
        return null
    }

    private fun parseNetworks(data: JsonObject): List<WifiNetworkInfo>? {
        val networks = data["networks"] as? JsonArray ?: return null
        return networks.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            WifiNetworkInfo(
                name = name,
                rssi = obj["rssi"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                encrypted = obj["isEncrypt"]?.jsonPrimitive?.booleanOrNull ?: false,
                bssid = obj["bssid"]?.jsonPrimitive?.content ?: ""
            )
        }
    }

    fun pair(wifiSsid: String, wifiPass: String) {
        val current = _state.value as? PairingState.WifiList ?: return
        val wsClient = ws ?: return
        val host = BuildConfig.MQTT_HOST
        if (host.isBlank()) {
            setState(PairingState.Failed("Chưa cấu hình MQTT_HOST trong local.properties"))
            return
        }
        setState(PairingState.SendingPair)
        scope.launch {
            var cred = mqttCredential
            if (cred == null || !cred.isValid()) {
                cred = fetchMqttCredential()
                mqttCredential = cred
            }
            if (cred == null || !cred.isValid()) {
                setState(PairingState.Failed("Không tải được credential MQTT từ Supabase — kiểm tra đăng nhập và kết nối mạng"))
                return@launch
            }
            Log.d(TAG, "pair(): ssid='$wifiSsid' (pass len=${wifiPass.length}) user=${cred.username}")
            val controlKey = ControlKeyStore.generateHex()
            val payload = buildJsonObject {
                put("wifiSsid", wifiSsid)
                put("wifiPass", wifiPass)
                put("controlKey", controlKey)
                put("mqttServer", host)
                put("mqttPort", BuildConfig.MQTT_PORT)
                put("mqttUser", cred.username)
                put("mqttPass", cred.password)
            }
            val resp = wsClient.request("pair", payload)
            Log.d(TAG, "pair(): response = $resp")
            val ok = resp != null && resp["status"]?.jsonPrimitive?.content == "ok"
            val deviceId = resp?.get("deviceId")?.jsonPrimitive?.content ?: current.deviceId
            keyStore.save(deviceId, controlKey)
            if (ok) {
                Log.d(TAG, "pair(): sending reboot to device")
                runCatching { wsClient.request("reboot", timeoutMs = 2_000) }
            }
            wsClient.close()
            connector.disconnect()
            if (ok) {
                Log.d(TAG, "pair(): SUCCESS deviceId=$deviceId — claiming device")
                val name = displayName(current.profile)
                setState(PairingState.Claiming(deviceId, current.profile, controlKey))
                claimAndRoute(PendingClaim(deviceId, current.profile, name, controlKey))
            } else {
                setState(PairingState.Failed(
                    resp?.get("message")?.jsonPrimitive?.content ?: "Cấu hình thất bại"
                ))
            }
        }
    }

    fun retryClaim() {
        val pending = lastPending ?: return
        setState(PairingState.Claiming(pending.deviceId, pending.profile, pending.controlKeyHex ?: ""))
        claimAndRoute(pending)
    }

    private var lastPending: PendingClaim? = null

    private fun claimAndRoute(pending: PendingClaim) {
        lastPending = pending
        scope.launch {
            when (val r = claimDevice(pending)) {
                ClaimResult.Success -> setState(
                    PairingState.Claimed(pending.deviceId, pending.profile, pending.controlKeyHex ?: "")
                )
                ClaimResult.SavedOffline -> setState(
                    PairingState.ClaimSavedOffline(pending.deviceId, pending.profile, pending.controlKeyHex ?: "")
                )
                ClaimResult.NeedsLogin -> setState(
                    PairingState.ClaimFailed(
                        pending.deviceId, pending.profile, pending.controlKeyHex ?: "",
                        "Cần đăng nhập để lưu thiết bị vào tài khoản — đăng nhập rồi bấm Thử lại"
                    )
                )
                is ClaimResult.Error -> setState(
                    PairingState.ClaimFailed(
                        pending.deviceId, pending.profile, pending.controlKeyHex ?: "", r.message
                    )
                )
            }
        }
    }

    /**
     * Claim thiết bị lên Supabase. Retry tối đa ~90s cho lỗi mạng/DNS (phone
     * vừa rời AP thiết bị, WiFi nhà chưa nối lại). Hết thời gian → lưu tạm
     * cục bộ (PendingClaimStore), DeviceManagerRepository tự đồng bộ sau.
     */
    private suspend fun claimDevice(pending: PendingClaim): ClaimResult {
        val session = SupabaseConfig.client.auth.sessionStatus.value
        if (session !is SessionStatus.Authenticated) return ClaimResult.NeedsLogin

        // Chờ 3.5s để Android OS khôi phục lại kết nối WiFi nhà / 4G sau khi rời AP thiết bị
        delay(INITIAL_CLAIM_DELAY_MS)

        val deadline = System.currentTimeMillis() + CLAIM_RETRY_TOTAL_MS
        while (System.currentTimeMillis() < deadline) {
            val r = deviceManager.addDevice(pending.name, pending.profile, pending.deviceId, pending.controlKeyHex)
            if (r.isSuccess) return ClaimResult.Success
            val cause = r.exceptionOrNull()
            if (!DeviceManagerRepository.isTransientNetworkError(cause)) {
                return ClaimResult.Error(cause?.message ?: "Không lưu được thiết bị")
            }
            Log.d(TAG, "claimDevice(): retrying for ${pending.deviceId}: ${cause?.message}")
            delay(CLAIM_RETRY_STEP_MS)
        }
        // Hết thời gian chờ mạng → lưu tạm, đồng bộ khi có mạng
        PendingClaimStore(context).add(pending)
        Log.w(TAG, "claimDevice(): network timeout — saved pending ${pending.deviceId}")
        return ClaimResult.SavedOffline
    }

    fun controlKeyFor(deviceId: String): String? = keyStore.get(deviceId)

    fun retryFromFailure() {
        connectSystemChooser()
    }

    fun cleanup() {
        Log.d(TAG, "cleanup()")
        scanner.stop()
        connector.disconnect()
        ws?.close()
        ws = null
    }

    companion object {
        private const val TAG = "MyHomePairing"
        const val DEVICE_AP_PASSWORD = "123456789"
        private const val CLAIM_RETRY_TOTAL_MS = 90_000L
        private const val CLAIM_RETRY_STEP_MS = 2_000L
        private const val INITIAL_CLAIM_DELAY_MS = 3_500L
    }

    private fun displayName(profile: String): String = when (profile.lowercase()) {
        "pump" -> "Máy bơm"
        "switch" -> "Công tắc"
        "fan" -> "Quạt"
        "lamp" -> "Đèn"
        else -> profile
    }
}
