package com.nndai.myhome.data.pairing

import android.content.Context
import android.util.Log
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

data class WifiNetworkInfo(
    val name: String,
    val rssi: Int,
    val encrypted: Boolean
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
    data class Paired(
        val deviceId: String,
        val profile: String,
        val controlKeyHex: String
    ) : PairingState
    data class Failed(val message: String) : PairingState
}

class PairingRepository(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val scanner = WifiDeviceScanner(context)
    private val connector = DeviceApConnector(context)
    private val keyStore = ControlKeyStore(context)

    val scanDevices: StateFlow<List<PairingDevice>> = scanner.devices
    val scanInProgress: StateFlow<Boolean> = scanner.scanning

    private val _state = MutableStateFlow<PairingState>(PairingState.Idle)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    private var ws: FirmwareWsClient? = null
    private var lastGateway: String? = null

    private fun setState(next: PairingState) {
        Log.d(TAG, "STATE: ${_state.value::class.simpleName} -> ${next::class.simpleName} ($next)")
        _state.value = next
    }

    init {
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
        val current = _state.value as? PairingState.DeviceReady ?: return
        Log.d(TAG, "scanWifiOnDevice(): device=${current.deviceId}")
        setState(PairingState.ScanningWifi(current.deviceId, current.profile))
        scope.launch {
            val networks = scanWifiSync(current)
            if (networks == null) {
                Log.e(TAG, "scanWifiOnDevice(): scan failed")
                setState(PairingState.Failed("Quét WiFi thất bại — hãy thử lại"))
            } else {
                Log.d(TAG, "scanWifiOnDevice(): got ${networks.size} networks")
                setState(PairingState.WifiList(current.deviceId, current.profile, networks))
            }
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
        val completed = CompletableDeferred<Boolean>()
        wsClient.setEventHandler { doc ->
            if (doc["cmd"]?.jsonPrimitive?.content == "scanWifi" &&
                doc["status"]?.jsonPrimitive?.content == "completed"
            ) {
                Log.d(TAG, "scanWifiSync(): device sent 'completed' event: $doc")
                completed.complete(true)
            } else {
                Log.d(TAG, "scanWifiSync(): event: $doc")
            }
        }
        val done = try {
            withTimeout(30_000) { completed.await() }
        } catch (e: Exception) {
            Log.e(TAG, "scanWifiSync(): timed out waiting 'completed'")
            false
        }
        if (!done) return null
        val data = wsClient.request("getScanWifiData") ?: run {
            Log.e(TAG, "scanWifiSync(): getScanWifiData returned null")
            return null
        }
        return parseNetworks(data) ?: run {
            Log.e(TAG, "scanWifiSync(): no 'networks' array in $data")
            emptyList()
        }
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
        val deadline = System.currentTimeMillis() + 90_000
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
                encrypted = obj["isEncrypt"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }
    }

    fun pair(wifiSsid: String, wifiPass: String) {
        val current = _state.value as? PairingState.WifiList ?: return
        val wsClient = ws ?: return
        Log.d(TAG, "pair(): ssid='$wifiSsid' (pass len=${wifiPass.length})")
        setState(PairingState.SendingPair)
        scope.launch {
            val controlKey = ControlKeyStore.generateHex()
            val payload = buildJsonObject {
                put("wifiSsid", wifiSsid)
                put("wifiPass", wifiPass)
                put("controlKey", controlKey)
            }
            val resp = wsClient.request("pair", payload)
            Log.d(TAG, "pair(): response = $resp")
            val ok = resp != null && resp["status"]?.jsonPrimitive?.content == "ok"
            val deviceId = resp?.get("deviceId")?.jsonPrimitive?.content ?: current.deviceId
            keyStore.save(deviceId, controlKey)
            wsClient.close()
            connector.disconnect()
            if (ok) {
                Log.d(TAG, "pair(): SUCCESS deviceId=$deviceId")
                setState(PairingState.Paired(deviceId, current.profile, controlKey))
            } else {
                setState(PairingState.Failed(
                    resp?.get("message")?.jsonPrimitive?.content ?: "Cấu hình thất bại"
                ))
            }
        }
    }

    fun controlKeyFor(deviceId: String): String? = keyStore.get(deviceId)

    fun retryFromFailure() {
        Log.d(TAG, "retryFromFailure()")
        setState(PairingState.Idle)
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
    }
}
