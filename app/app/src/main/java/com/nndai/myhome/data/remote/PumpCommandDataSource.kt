package com.nndai.myhome.data.remote

import android.util.Log
import com.nndai.myhome.data.model.DeviceConfig
import com.nndai.myhome.data.model.DeviceInfo
import com.nndai.myhome.data.model.PumpState
import com.nndai.myhome.data.model.PumpStatus
import com.nndai.myhome.data.model.RemoteFileEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Tầng gửi/nhận JSON tới device. Serialize command → JSON, parse JSON → event.
 * Không quan tâm kênh vật lý, chỉ cần một DeviceChannel.
 */
class PumpCommandDataSource(
    private val channel: DeviceChannel,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val _events = MutableSharedFlow<PumpCommandEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<PumpCommandEvent> = _events.asSharedFlow()

    init {
        scope.launch(dispatcher) {
            channel.incoming.collect { raw ->
                Log.d(TAG, "incoming payload=\n$raw")
                handleIncoming(raw)
            }
        }
    }

    // ── Send commands ──

    suspend fun turnOn() {
        sendCommand("setRelay", JSONObject().apply { put("state", true) })
    }

    suspend fun turnOff() {
        sendCommand("setRelay", JSONObject().apply { put("state", false) })
    }

    suspend fun getStatus(stream: Boolean = false) {
        val payload = if (stream) JSONObject().apply { put("stream", true) } else null
        sendCommand("getStatus", payload)
    }

    suspend fun getConfig() {
        sendCommand("getConfig")
    }

    suspend fun scanWifi() {
        sendCommand("scanWifi")
    }

    suspend fun getScanWifiData() {
        sendCommand("getScanWifiData")
    }

    suspend fun calibrate(payload: Map<String, Any>) {
        sendCommand("calibrate", JSONObject(payload))
    }

    suspend fun resetCalibration() {
        sendCommand("resetCalibration")
    }

    suspend fun setConfig(updates: Map<String, Any>) {
        sendCommand("setConfig", JSONObject(updates))
    }

    suspend fun setDeviceMode(pumpMode: Boolean) {
        setConfig(mapOf("pumpMode" to pumpMode))
    }

    suspend fun getInfo(stream: Boolean = false) {
        sendCommand("getSystemInfo", JSONObject().apply { 
            put("fields", "all") 
            if (stream) put("stream", true)
        })
    }

    suspend fun reboot() {
        sendCommand("reboot", JSONObject())
    }

    suspend fun setLogMqtt(enabled: Boolean) {
        sendCommand("setLogMqtt", JSONObject().apply { put("enabled", enabled) })
    }

    suspend fun getLogMqtt() {
        sendCommand("getLogMqtt", JSONObject())
    }

    suspend fun factoryReset() {
        sendCommand("factoryReset")
    }

    suspend fun clearPumpFault() {
        sendCommand("clearPumpFault")
    }

    fun generateReqId(): String = "r_${System.currentTimeMillis()}_${(1000..9999).random()}"

    suspend fun listDir(path: String, offset: Long = 0, limit: Long = 0, customReqId: String? = null): String {
        val reqId = customReqId ?: generateReqId()
        sendCommand("listDir", JSONObject().apply {
            put("path", path)
            if (limit > 0) {
                put("offset", offset)
                put("limit", limit)
            }
        }, reqId = reqId)
        return reqId
    }

    suspend fun readFile(
        path: String,
        offset: Long = 0,
        limit: Long = 1024,
        encode: Boolean = false,
        customReqId: String? = null
    ): String {
        val reqId = customReqId ?: generateReqId()
        sendCommand("readFile", JSONObject().apply {
            put("path", path)
            put("offset", offset)
            put("limit", limit)
            put("encode", encode)
        }, reqId = reqId)
        return reqId
    }

    suspend fun deleteItem(path: String, customReqId: String? = null): String {
        val reqId = customReqId ?: generateReqId()
        sendCommand("deleteItem", JSONObject().apply {
            put("path", path)
        }, reqId = reqId)
        return reqId
    }

    // ── Private helpers ──

    private suspend fun sendCommand(cmd: String, payload: JSONObject? = null, reqId: String? = null) {
        val json = JSONObject().apply {
            put("cmd", cmd)
            if (!reqId.isNullOrEmpty()) {
                put("reqId", reqId)
            }
            if (payload != null) {
                put("payload", payload)
            }
        }
        sendJson(json)
    }

    suspend fun sendRawJson(rawJson: String): Boolean {
        Log.d(TAG, "sendRawJson payload=${rawJson.take(200)}")
        val sent = withContext(dispatcher) {
            channel.send(rawJson)
        }
        if (!sent) {
            _events.tryEmit(PumpCommandEvent.Failure("Cannot send raw JSON"))
        }
        return sent
    }

    private suspend fun sendJson(json: JSONObject) {
        val payload = json.toString()
        Log.d(TAG, "sendJson payload=${payload.take(200)}")
        val sent = withContext(dispatcher) {
            channel.send(payload)
        }
        if (!sent) {
            _events.tryEmit(
                PumpCommandEvent.Failure(
                    "Cannot send command: ${json.optString("cmd", "n/a")}"
                )
            )
        }
    }

    // ── Parse incoming ──

    private fun handleIncoming(raw: String) {
        try {
            val json = JSONObject(raw)
            val cmd = json.optString("cmd", "")
            Log.d(TAG, "handleIncoming cmd=$cmd")

            when (cmd) {
                "getStatus" -> emitStatus(json)
                "getConfig" -> emitConfig(json)
                "getSystemInfo" -> emitInfo(json)
                "setRelay", "turnOn", "turnOff", "setMode", "clearPumpFault" -> emitToggleResult(json)
                "log" -> emitLog(json)
                "getLogMqtt" -> _events.tryEmit(PumpCommandEvent.LogMqttStatus(json.optBoolean("enabled", false)))
                "scanWifi", "getScanWifiData" -> emitWifiScanResult(json)
                "listDir" -> emitListDirResult(json)
                "readFile", "downloadFile" -> emitReadFileResult(json)
                "deleteItem" -> emitDeleteItemResult(json)
                "setConfig", "setDeviceMode", "reboot", "factoryReset", "setLogMqtt", "calibrate", "resetCalibration" ->
                    emitCommandResult(cmd, json)
                else -> {
                    if (json.has("msg")) {
                        _events.tryEmit(PumpCommandEvent.LogMessage(json.optString("msg")))
                    } else if (json.has("log")) {
                        _events.tryEmit(PumpCommandEvent.LogMessage(json.optString("log")))
                    }
                    if (json.has("event")) {
                        Log.d(TAG, "handleIncoming event=${json.optString("event")}")
                    }
                }
            }
        } catch (ex: JSONException) {
            Log.d(TAG, "handleIncoming() raw string message: $raw")
            if (raw.isNotBlank()) {
                _events.tryEmit(PumpCommandEvent.LogMessage(raw))
            }
        }
    }

    private fun emitStatus(json: JSONObject) {
        val status = PumpStatus(
            relay = json.optBoolean("relay", false),
            current = json.optDouble("current", 0.0).toFloat(),
            power = json.optDouble("power", 0.0).toFloat(),
            voltage = json.optDouble("voltage", 0.0).toFloat(),
            dailyEnergy = json.optDouble("dailyEnergy", 0.0).toFloat(),
            hourlyEnergy = json.optDouble("hourlyEnergy", 0.0).toFloat(),
            apparent = json.optDouble("apparent", 0.0).toFloat(),
            pf = json.optDouble("pf", 0.0).toFloat(),
            temperature = json.optDouble("temperature", 0.0).toFloat(),
            rssi = json.optInt("rssi", 0),
            uptime = json.optLong("uptime", 0),
            heap = json.optLong("heap", 0),
            pumpMode = json.optBoolean("pumpMode", true),
            pumpState = PumpState.fromCodeOrString(
                code = if (json.has("pumpState")) json.optInt("pumpState") else null,
                str = json.optString("pumpStateStr", json.optString("pumpState"))
            ),
            timestamp = json.optLong("timestamp", 0L),
            targetId = json.optString("targetId", ""),
            targetType = json.optString("targetType", ""),
            targetPaired = json.optBoolean("targetPaired", false),
            targetError = json.optBoolean("targetError", false)
        )
        _events.tryEmit(PumpCommandEvent.StatusUpdate(status))
        _events.tryEmit(PumpCommandEvent.CommandResult("getStatus", true))
    }

    private fun emitConfig(json: JSONObject) {
        val config = DeviceConfig(
            connMode = json.optInt("connMode", 0),
            mqttServer = json.optString("mqttServer", ""),
            mqttPort = json.optInt("mqttPort", 8883),
            mqttUser = json.optString("mqttUser", ""),
            mqttPass = json.optString("mqttPass", ""),
            mqttTopic = json.optString("mqttTopic", ""),
            wifiSSID = json.optString("wifiSSID", ""),
            wifiPass = json.optString("wifiPass", ""),
            apSSID = json.optString("apSSID", ""),
            apPass = json.optString("apPass", ""),
            debugSSID = json.optString("debugSSID", ""),
            debugPass = json.optString("debugPass", ""),
            debugIp = json.optString("debugIp", ""),
            debugGateway = json.optString("debugGateway", ""),
            debugNetmask = json.optString("debugNetmask", ""),
            pumpMode = json.optBoolean("pumpMode", true),
            threshOff = json.optInt("threshOff", 100),
            threshNoWater = json.optInt("threshNoWater", 2000),
            threshRunning = json.optInt("threshRunning", 5000),
            threshOverload = json.optInt("threshOverload", 20000),
            dryTimeout = json.optInt("dryTimeout", 7000),
            overloadTimeout = json.optInt("overloadTimeout", 1000),
            relayStartMode = json.optInt("relayStartMode", 0),
            cCal = json.optDouble("cCal", 1.0),
            vCal = json.optDouble("vCal", 1.0),
            pCal = json.optDouble("pCal", 1.0),
            sysLogFileEnabled = json.optBoolean("sysLogFileEnabled", false),
            sysLogFileLevel = json.optInt("sysLogFileLevel", 0),
            firmware = json.optString("firmware", ""),
            targetId = json.optString("targetId", ""),
            targetType = json.optString("targetType", ""),
            targetKey = json.optString("targetKey", "")
        )
        _events.tryEmit(PumpCommandEvent.ConfigUpdate(config))
        _events.tryEmit(PumpCommandEvent.CommandResult("getConfig", true))
    }

    private fun emitInfo(json: JSONObject) {
        val rawData = mutableMapOf<String, Any>()
        
        json.keys().forEach { key ->
            if (key != "cmd" && key != "status") {
                val value = json.opt(key)
                if (value != null) {
                    rawData[key] = parseJsonValue(value)
                }
            }
        }
        
        val info = DeviceInfo(data = rawData)
        _events.tryEmit(PumpCommandEvent.InfoUpdate(info))
        _events.tryEmit(PumpCommandEvent.CommandResult("getSystemInfo", true))
    }

    private fun parseJsonValue(value: Any): Any {
        return when (value) {
            is JSONObject -> {
                val map = mutableMapOf<String, Any>()
                value.keys().forEach { k ->
                    map[k] = parseJsonValue(value.get(k))
                }
                map
            }
            is JSONArray -> {
                val list = mutableListOf<Any>()
                for (i in 0 until value.length()) {
                    list.add(parseJsonValue(value.get(i)))
                }
                list
            }
            else -> value
        }
    }

    private fun emitToggleResult(json: JSONObject) {
        val cmd = json.optString("cmd")
        val success = json.optString("status") == "ok"
        // Pass relay state as message to help repository update UI instantly
        val msg = if (success && json.has("state")) json.optString("state") else json.optString("message").takeIf { it.isNotEmpty() }
        _events.tryEmit(PumpCommandEvent.CommandResult(cmd, success, msg))
    }

    private fun emitLog(json: JSONObject) {
        val msg = json.optString("msg")
        if (msg.isNotEmpty()) {
            _events.tryEmit(PumpCommandEvent.LogMessage(msg))
        }
    }

    private fun emitCommandResult(cmd: String, json: JSONObject) {
        val success = json.optString("status") == "ok"
        val needReboot = json.optBoolean("needReboot", false)
        _events.tryEmit(
            PumpCommandEvent.CommandResult(
                command = cmd,
                success = success,
                message = json.optString("message").takeIf { it.isNotEmpty() },
                needReboot = needReboot
            )
        )
    }

    private fun emitWifiScanResult(json: JSONObject) {
        val status = json.optString("status", "")
        if (status == "completed") {
            _events.tryEmit(PumpCommandEvent.WifiScanCompleted)
            return
        }
        if (status == "error") {
            val msg = json.optString("message", "WiFi scan failed")
            _events.tryEmit(PumpCommandEvent.WifiScanResult(success = false, message = msg))
            return
        }
        if (json.has("networks")) {
            val array = json.optJSONArray("networks") ?: org.json.JSONArray()
            val list = mutableListOf<WifiNetwork>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val nameStr = when {
                    item.has("name") -> item.optString("name", "")
                    else -> item.optString("ssid", "")
                }
                val isEncryptBool = when {
                    item.has("isEncrypt") -> item.optBoolean("isEncrypt", true)
                    else -> !item.optString("encryption", "").equals("Open", ignoreCase = true)
                }
                list.add(
                    WifiNetwork(
                        name = nameStr,
                        rssi = item.optInt("rssi", -100),
                        bssid = item.optString("bssid", ""),
                        isEncrypt = isEncryptBool
                    )
                )
            }
            _events.tryEmit(PumpCommandEvent.WifiScanResult(success = true, networks = list))
        }
    }

    private fun emitListDirResult(json: JSONObject) {
        val status = json.optString("status", "")
        val success = status == "ok"
        val path = json.optString("path", "")
        val reqId = if (json.has("reqId")) json.optString("reqId").takeIf { it.isNotEmpty() } else null
        val entriesList = mutableListOf<RemoteFileEntry>()
        val array = json.optJSONArray("entries")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                entriesList.add(
                    RemoteFileEntry(
                        name = item.optString("name", ""),
                        type = item.optString("type", "file"),
                        size = item.optLong("size", 0L)
                    )
                )
            }
        }
        val total = json.optInt("total", entriesList.size)
        val more = json.optBoolean("more", false)
        _events.tryEmit(
            PumpCommandEvent.ListDirResult(
                path = path,
                entries = entriesList,
                total = total,
                more = more,
                success = success,
                message = if (!success) json.optString("message", "List dir failed") else null,
                reqId = reqId
            )
        )
    }

    private fun emitReadFileResult(json: JSONObject) {
        val status = json.optString("status", "")
        val success = status == "ok"
        val path = json.optString("path", "")
        val data = json.optString("data", "")
        val offset = json.optLong("offset", 0L)
        val size = json.optLong("size", 0L)
        val more = json.optBoolean("more", false)
        val reqId = if (json.has("reqId")) json.optString("reqId").takeIf { it.isNotEmpty() } else null
        _events.tryEmit(
            PumpCommandEvent.ReadFileResult(
                path = path,
                data = data,
                offset = offset,
                size = size,
                more = more,
                success = success,
                message = if (!success) json.optString("message", "Read file failed") else null,
                reqId = reqId
            )
        )
    }

    private fun emitDeleteItemResult(json: JSONObject) {
        val status = json.optString("status", "")
        val success = status == "ok"
        val path = json.optString("path", "")
        val reqId = if (json.has("reqId")) json.optString("reqId").takeIf { it.isNotEmpty() } else null
        val message = json.optString("message").takeIf { it.isNotEmpty() }
        _events.tryEmit(
            PumpCommandEvent.DeleteItemResult(
                success = success,
                path = path,
                reqId = reqId,
                message = message
            )
        )
    }

    companion object {
        private const val TAG = "PumpCommandDataSource"
    }
}
