#include "core/CommandHandler.h"
#include "compat/log.h"
#include "core/BuildInfo.h"
#include "core/Crypto.h"
#include "compat/wifi_scan.h"
#include "chip/scan.h"
#include "chip/io.h"

extern ConnMode g_connMode;

template <typename T>
CommandHandlerT<T>::CommandHandlerT()
    : _cfg(nullptr)
    , _driver(nullptr)
    , _log(nullptr)
    , _ota(nullptr)
{
}

template <typename T>
void CommandHandlerT<T>::begin(ConfigManagerT<T>* cfg, LogManager* log,
    OTAManager* ota, DeviceIdentity* identity, const char* profile) {
    _cfg = cfg;
    _log = log;
    _ota = ota;
    _identity = identity;
    _profile = profile ? profile : "unknown";

    // Per-sender seq table lives in RAM only (intentional: no flash persistence).
    // After reboot the ts window (60s) guards replay until NTP sync.
    _seqCount = 0;
}

template <typename T>
void CommandHandlerT<T>::setResponseCallback(ResponseCallback cb) {
    _responseCb = cb;
}

template <typename T>
void CommandHandlerT<T>::handleCommand(const String& source, const String& json) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, json);
    if (err) {
        JsonDocument resp;
        resp["status"] = "error";
        resp["message"] = "Invalid JSON";
        _sendResponse(source, resp);
        return;
    }

    const char* cmd = doc["cmd"];
    JsonVariant payloadVar = doc["payload"];

    if (!cmd) {
        JsonDocument resp;
        resp["status"] = "error";
        resp["message"] = "Missing cmd";
        _sendResponse(source, resp);
        return;
    }

    JsonDocument payload;
    if (!payloadVar.isNull()) {
        payload = payloadVar.as<JsonObject>();
    }

    _handleCommand(source, doc, payload);
}

template <typename T>
void CommandHandlerT<T>::_sendResponse(const String& source, const String& json) {
    if (_responseCb) _responseCb(source, json);
}

// ── Envelope verify (docs §3.2) ──
// Lệnh MQTT hợp lệ: {cmd, payload, seq, ts, hmac, src} với
//   hmac = HMAC-SHA256(controlKey, "<seq>|<ts>|<cmd>|<payload JSON compact>|<src>")
// Kiểm tra: seq tăng dần THEO TỪNG SENDER (chống replay, RAM-only), |now-ts| <= 60s,
// HMAC đúng (chống giả mạo — kẻ khác không có controlKey).
template <typename T>
bool CommandHandlerT<T>::_verifyEnvelope(const JsonDocument& cmd, const JsonDocument& payload) {
    if (!_identity) {
        LT_EM(CMD, "Envelope: identity unavailable");
        return false;
    }

    if (!cmd["seq"].is<uint32_t>() || !cmd["ts"].is<uint32_t>() || !cmd["hmac"].is<const char*>()) {
        LT_EM(CMD, "Envelope: missing seq/ts/hmac");
        return false;
    }
    const uint32_t seq = cmd["seq"].as<uint32_t>();
    const uint32_t ts = cmd["ts"].as<uint32_t>();
    const char* hmacHex = cmd["hmac"].as<const char*>();

    if (strlen(hmacHex) != 64) {
        LT_EM(CMD, "Envelope: bad hmac length");
        return false;
    }

    // Nhiều controller (app, remote switch...) ký cùng controlKey nhưng giữ seq
    // riêng → floor riêng cho từng sender ("" = legacy sender thiếu src).
    const char* src = cmd["src"] | "";

    // Lệch giờ: chỉ kiểm tra khi đã đồng bộ NTP (now != 0).
    if (_log->isTimeSynced()) {
        const uint32_t now = _log->getEpoch();
        if (now != 0) {
            const int32_t drift = (int32_t)(ts - now);
            if (drift < -(int32_t)ENVELOPE_TS_WINDOW_S || drift > (int32_t)ENVELOPE_TS_WINDOW_S) {
                LT_EM(CMD, "Envelope: ts drift %d s", (int)drift);
                return false;
            }
        }
    }

    // Canonical: "seq|ts|cmd|payload|src" — payload serialize lại từ doc (compact, giữ thứ tự key).
    String keyHex;
    if (!_identity->controlKeyHex(keyHex)) {
        LT_EM(CMD, "Envelope: no controlKey");
        return false;
    }
    String payloadStr;
    serializeJson(payload, payloadStr);
    const String canonical = crypto::buildCanonical(seq, ts, cmd["cmd"].as<const char*>(), payloadStr, src);

    char expectedHex[65];
    if (!crypto::hmacSha256HexKey(keyHex.c_str(), canonical.c_str(), canonical.length(), expectedHex)) {
        LT_EM(CMD, "Envelope: hmac compute failed");
        return false;
    }
    if (strcmp(expectedHex, hmacHex) != 0) {
        LT_EM(CMD, "Envelope: hmac mismatch (cmd=%s)", cmd["cmd"].as<const char*>());
        return false;
    }

    // ── HMAC hợp lệ mới được phép thay đổi bảng seq ──
    // Nếu cấp slot trước verify, kẻ spam (có credential MQTT shared nhưng
    // không có controlKey) có thể lấp đầy MAX_SEQ_ENTRIES bằng src giả →
    // DoS khóa cả sender chính chủ tới khi reboot.

    SeqEntry* entry = nullptr;
    for (uint8_t i = 0; i < _seqCount; i++) {
        if (strcmp(_seqTable[i].src, src) == 0) {
            entry = &_seqTable[i];
            break;
        }
    }
    if (!entry) {
        if (_seqCount >= MAX_SEQ_ENTRIES) {
            LT_EM(CMD, "Envelope: too many senders (max %u)", (unsigned)MAX_SEQ_ENTRIES);
            return false;
        }
        if (strlen(src) >= sizeof(entry->src)) {
            LT_EM(CMD, "Envelope: src too long");
            return false;
        }
        entry = &_seqTable[_seqCount++];
        strlcpy(entry->src, src, sizeof(entry->src));
        entry->seq = 0;
    }

    // Replay: seq phải lớn hơn seq cuối đã duyệt của SENDER này.
    if (seq <= entry->seq) {
        LT_EM(CMD, "Envelope: stale seq %u (last %u) from '%s'", (unsigned)seq, (unsigned)entry->seq, src);
        return false;
    }

    entry->seq = seq;
    // Cố ý KHÔNG persist floor xuống flash: sau reboot bảng rỗng, replay bị chặn
    // bằng cửa sổ ts 60s — đánh đổi flash wear vs an toàn đã được chấp nhận.
    return true;
}

// Reset floor của mọi sender — gọi khi controlKey đổi (pair mới / provision) hoặc factory reset.
template <typename T>
void CommandHandlerT<T>::_resetSeq() {
    _seqCount = 0;
}

template <typename T>
void CommandHandlerT<T>::_sendResponse(const String& source, const JsonDocument& doc) {
    String json;
    serializeJson(doc, json);
    _sendResponse(source, json);
}

template <typename T>
void CommandHandlerT<T>::_handleCommand(const String& source, const JsonDocument& cmd, const JsonDocument& payload) {
    String cmdStr = cmd["cmd"].as<String>();
    String reqId = cmd["reqId"].is<String>() ? cmd["reqId"].as<String>() : "";

    // ── Lệnh từ MQTT PHẢI có envelope hợp lệ (seq/ts/hmac) — docs §3.2 ──
    // Chặn: giả mạo (kẻ khác publish lệnh), replay (seq cũ), lệch giờ (ts).
    // AP/WS (pairing) không cần envelope: proximity + WPA2.
    if (source == "mqtt" && !_verifyEnvelope(cmd, payload)) {
        LT_EM(CMD, "Mqtt command '%s' rejected: invalid envelope", cmdStr.c_str());
        return;
    }

    JsonDocument resp;
    resp["cmd"] = cmdStr;
    
    if (reqId.length() > 0) resp["reqId"] = reqId;

    // Command riêng của thiết bị → chuyển cho driver
    if (_driver && _driver->handleCmd(cmdStr.c_str(), payload, resp)) {
        _sendResponse(source, resp);
        return;
    }

    if (cmdStr == "getStatus") _cmdGetStatus(source, payload, resp);
    else if (cmdStr == "getConfig") _cmdGetConfig(source, payload, resp);
    else if (cmdStr == "setConfig") _cmdSetConfig(source, payload, resp);
    else if (cmdStr == "getLog") _cmdGetLog(source, payload, resp);
    else if (cmdStr == "clearSysLog") _cmdClearSysLog(source, payload, resp);
    else if (cmdStr == "otaUrl") _cmdOtaUrl(source, payload, resp);
    else if (cmdStr == "reboot") _cmdReboot(source, payload, resp);
    else if (cmdStr == "factoryReset") _cmdFactoryReset(source, payload, resp);
    else if (cmdStr == "setLogMqtt") _cmdSetLogMqtt(source, payload, resp);
    else if (cmdStr == "getLogMqtt") _cmdGetLogMqtt(source, payload, resp);
    else if (cmdStr == "getLogStats") _cmdGetLogStats(source, payload, resp);
    else if (cmdStr == "getSystemInfo") _cmdGetSystemInfo(source, payload, resp);
    else if (cmdStr == "uploadFirmwareStart") _cmdUploadFirmwareStart(source, payload, resp);
    else if (cmdStr == "uploadFirmwareEnd") _cmdUploadFirmwareEnd(source, payload, resp);
    else if (cmdStr == "uploadFirmwareAbort") _cmdUploadFirmwareAbort(source, payload, resp);
    else if (cmdStr == "otaChunk") _cmdOtaChunk(source, payload, resp);
    else if (cmdStr == "scanWifi") _cmdScanWifi(source, payload, resp);
    else if (cmdStr == "getScanWifiData") _cmdGetScanWifiData(source, payload, resp);
    else if (cmdStr == "pair") _cmdPair(source, payload, resp);
    else if (cmdStr == "provision") _cmdProvision(source, payload, resp);
    else if (cmdStr == "listDir" || cmdStr == "readFile" || cmdStr == "fileInfo" || cmdStr == "deleteItem" || cmdStr == "fsInfo" || cmdStr == "downloadFile") {
        _handleFileCommand(source, cmdStr, payload, reqId);
        return;
    }
    else {
        resp["status"] = "error";
        resp["message"] = "Unknown command";
        _sendResponse(source, resp);
    }
}

template <typename T>
void CommandHandlerT<T>::startStream(StreamType type, const String& source, unsigned long durationMs) {
    if (type >= STREAM_COUNT) return;
    _streamDeadline[type] = millis() + durationMs;
    _streamSource[type] = source;
}

template <typename T>
bool CommandHandlerT<T>::isStreamActive(StreamType type) const {
    if (type >= STREAM_COUNT) return false;
    return _streamSource[type].length() > 0 && millis() < _streamDeadline[type];
}

template <typename T>
bool CommandHandlerT<T>::anyStreamActive() const {
    for (int i = 0; i < STREAM_COUNT; i++) {
        if (isStreamActive((StreamType)i)) return true;
    }
    return false;
}

template <typename T>
void CommandHandlerT<T>::sendStream(StreamType type) {
    if (!isStreamActive(type)) return;

    JsonDocument resp;
    JsonDocument emptyPayload;
    const String& source = _streamSource[type];

    switch (type) {
    case STREAM_STATUS:
        resp["cmd"] = "getStatus";
        _cmdGetStatus(source, emptyPayload, resp);
        break;
    case STREAM_SYSINFO:
        resp["cmd"] = "getSystemInfo";
        emptyPayload["fields"] = "all";
        _cmdGetSystemInfo(source, emptyPayload, resp);
        break;
        case STREAM_COUNT:
        default:
            break;
    }
}

template <typename T>
void CommandHandlerT<T>::publishStatusToUp() {
    JsonDocument resp;
    JsonDocument emptyPayload;
    resp["cmd"] = "getStatus";
    _cmdGetStatus("mqtt", emptyPayload, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetStatus(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    
    resp["timestamp"] = _log->getEpoch();

    resp["status"] = "ok";
    resp["rssi"] = WiFi.RSSI();
    if (_driver) _driver->getStatus(resp);

    _sendResponse(source, resp);

    if (payload["stream"].is<bool>() && payload["stream"].as<bool>()) {
        startStream(STREAM_STATUS, source, STREAM_DURATION_MS);
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdGetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    T& c = _cfg->get();
    resp["status"] = "ok";

    // Connection mode
    resp["connMode"] = (int)c.connMode;

    // MQTT — topic là "devices/{deviceId}" (chuẩn phase 2, không cấu hình tay)
    resp["mqttServer"] = c.mqttServer;
    resp["mqttPort"] = c.mqttPort;
    resp["mqttUser"] = c.mqttUser;
    resp["mqttPass"] = strlen(_cfg->passPlain()) > 0 ? "********" : "";
    if (_identity) {
        resp["mqttTopic"] = String("devices/") + _identity->deviceId();
    } else {
        resp["mqttTopic"] = "";
    }

    // WiFi
    resp["wifiSSID"] = c.wifiSSID;
    resp["wifiPass"] = strlen(c.wifiPass) > 0 ? "********" : "";
    resp["debugSSID"] = c.debugSSID;
    resp["debugPass"] = strlen(c.debugPass) > 0 ? "********" : "";

    // Danh tính + pairing (AP SSID tự suy ra từ deviceId — không cấu hình tay)
    resp["deviceId"] = _identity ? _identity->deviceId() : "";
    resp["apSSID"] = _identity ? _identity->apSSID() : "";
    resp["profile"] = _profile;
    resp["pairingState"] = (_identity && _identity->isProvisioned()) ? "provisioned" : "unprovisioned";

    // Debug network settings
    char ipBuf[16];
    snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", c.debugIp[0], c.debugIp[1], c.debugIp[2], c.debugIp[3]);
    resp["debugIp"] = (const char*)ipBuf;
    snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", c.debugGateway[0], c.debugGateway[1], c.debugGateway[2], c.debugGateway[3]);
    resp["debugGateway"] = (const char*)ipBuf;
    snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", c.debugNetmask[0], c.debugNetmask[1], c.debugNetmask[2], c.debugNetmask[3]);
    resp["debugNetmask"] = (const char*)ipBuf;

    resp["sysLogFileEnabled"] = c.sysLogFileEnabled;
    resp["sysLogFileLevel"] = c.sysLogFileLevel;

    // Field riêng của thiết bị
    if (_driver) _driver->getConfig(resp);

    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdSetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    T& c = _cfg->get();
    bool changed = false;
    bool needReboot = false;

    // Connection mode
    if (payload["connMode"].is<unsigned int>()) {
        int v = payload["connMode"].as<int>();
        if (v >= 0 && v <= 2) {
            c.connMode = (ConnMode)v;
            changed = true;
            needReboot = true;
        }
    }

    // MQTT settings
    if (payload["mqttServer"].is<const char*>()) {
        strlcpy(c.mqttServer, payload["mqttServer"], sizeof(c.mqttServer));
        changed = true;
        needReboot = true;
    }
    if (payload["mqttPort"].is<unsigned int>()) { 
        c.mqttPort = payload["mqttPort"]; 
        changed = true;
        needReboot = true;
    }
    if (payload["mqttUser"].is<const char*>()) {
        strlcpy(c.mqttUser, payload["mqttUser"], sizeof(c.mqttUser));
        changed = true;
        needReboot = true;
    }
    if (payload["mqttPass"].is<const char*>()) { 
        _cfg->setPassPlain(payload["mqttPass"].as<const char*>());
        changed = true; 
        needReboot = true;
    }
    // mqttTopic KHÔNG cấu hình tay nữa — topic chuẩn "devices/{deviceId}" (xem getConfig).

    // WiFi STA settings
    if (payload["wifiSSID"].is<const char*>()) { 
        strlcpy(c.wifiSSID, payload["wifiSSID"], sizeof(c.wifiSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload["wifiPass"].is<const char*>()) { 
        strlcpy(c.wifiPass, payload["wifiPass"], sizeof(c.wifiPass)); 
        changed = true; 
        needReboot = true;
    }

    // WiFi DEBUG settings
    if (payload["debugSSID"].is<const char*>()) { 
        strlcpy(c.debugSSID, payload["debugSSID"], sizeof(c.debugSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload["debugPass"].is<const char*>()) { 
        strlcpy(c.debugPass, payload["debugPass"], sizeof(c.debugPass)); 
        changed = true; 
        needReboot = true;
    }

    // Settings riêng của thiết bị → driver (pump thresholds, calib, relayStartMode...)
    if (_driver && _driver->setConfig(payload, resp)) {
        changed = true;
    }

    // Debug network settings
    auto parseIP = [](const char* s, uint8_t ip[4]) -> bool {
        return sscanf(s, "%hhu.%hhu.%hhu.%hhu", &ip[0], &ip[1], &ip[2], &ip[3]) == 4;
        };
    if (payload["debugIp"].is<const char*>()) { 
        changed |= parseIP(payload["debugIp"], c.debugIp); 
        needReboot = true;
    }
    if (payload["debugGateway"].is<const char*>()) { 
        changed |= parseIP(payload["debugGateway"], c.debugGateway); 
        needReboot = true;
    }
    if (payload["debugNetmask"].is<const char*>()) { 
        changed |= parseIP(payload["debugNetmask"], c.debugNetmask); 
        needReboot = true; 
    }

    // System log settings
    if (payload["sysLogFileEnabled"].is<bool>()) {
        c.sysLogFileEnabled = payload["sysLogFileEnabled"];
        _log->setSysLogFileEnabled(c.sysLogFileEnabled);
        changed = true;
    }
    if (payload["sysLogFileLevel"].is<unsigned int>()) {
        c.sysLogFileLevel = payload["sysLogFileLevel"];
        _log->setSysLogFileLevel(c.sysLogFileLevel);
        changed = true;
    }

    if (changed) {
        _cfg->save(c);
        resp["status"] = "ok";
        resp["message"] = needReboot ? "Config saved. Reboot required." : "Config saved.";
        resp["needReboot"] = needReboot;
        LT_IM(CMD, "Config updated%s", needReboot ? " (reboot needed)" : "");
    }
    else {
        resp["status"] = "ok";
        resp["message"] = "No changes";
    }

    _sendResponse(source, resp);
}


template <typename T>
void CommandHandlerT<T>::_cmdGetLog(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    String logContent;
    if (_log->readSysLog(logContent, 4096)) {
        resp["status"] = "ok";
        resp["log"] = logContent;
        resp["logSize"] = _log->getSysLogSize();
    }
    else {
        resp["status"] = "error";
        resp["message"] = "No log available";
    }
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdClearSysLog(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _log->clearSysLog();
    resp["status"] = "ok";
    resp["message"] = "Sys log cleared";
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdOtaUrl(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    const char* url = payload["url"];
    if (!url) {
        resp["status"] = "error";
        resp["message"] = "Missing URL";
        _sendResponse(source, resp);
        return;
    }
    resp["status"] = "ok";
    resp["message"] = "OTA started from URL";
    _sendResponse(source, resp);

    static int lastPct = -1;
    lastPct = -1;

    _ota->startFromUrl(url,
        [this, source](int progress, int total) {
            int pct = (total > 0) ? (progress * 100 / total) : 0;
            // Only send log every 5% to avoid flooding WebSocket
            if (pct >= lastPct + 5 || pct == 100 || (pct == 0 && pct != lastPct)) {
                lastPct = pct;
                LT_IM(OTA, "OTA Progress: %d%%", pct);
            }
        },
        [this, source](bool success, const char* msg) {
            LT_IM(OTA, "OTA upload %s: %s", success ? "success" : "failed", msg);
        }
    );
}

template <typename T>
void CommandHandlerT<T>::_cmdReboot(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp["status"] = "ok";
    resp["message"] = "Rebooting...";
    _sendResponse(source, resp);
    LT_IM(CMD, "Rebooting...");
    delay(1000);
    ESP.restart();
}

template <typename T>
void CommandHandlerT<T>::_cmdFactoryReset(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _cfg->reset();
    if (_identity) _identity->reset();
    _resetSeq(); // xóa seq đã duyệt — thiết bị quay về unprovisioned
    resp["status"] = "ok";
    resp["message"] = "Factory reset. Rebooting...";
    _sendResponse(source, resp);
    LT_IM(CMD, "Factory reset (config + identity)");
    delay(1000);
    ESP.restart();
}

template <typename T>
void CommandHandlerT<T>::_cmdSetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    bool en = payload["enabled"].as<bool>();
    _log->setMqttLogEnabled(en);
    resp["status"] = "ok";
    resp["enabled"] = en;
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp["status"] = "ok";
    resp["enabled"] = _log->isMqttLogEnabled();
    _sendResponse(source, resp);
}


template <typename T>
void CommandHandlerT<T>::_cmdGetLogStats(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp["status"] = "ok";
    resp["sysLogSize"] = _log->getSysLogSize();
    resp["toggleLogSize"] = _log->getToggleLogSize();
    resp["powerLogSize"] = _log->getPowerLogSize();
    resp["totalBytes"] = _log->getTotalBytes();
    resp["usedBytes"] = _log->getUsedBytes();
    resp["timeSynced"] = _log->isTimeSynced();
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareStart(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (!payload["size"].is<unsigned int>()) {
        resp["status"] = "error";
        resp["message"] = "Missing size";
        _sendResponse(source, resp);
        return;
    }
    size_t size = payload["size"].as<unsigned int>();
    bool isMqtt = (source == "mqtt");

    static int lastPct = -1;
    lastPct = -1;
    bool ok = _ota->startFromStream(size,
        [this, source, isMqtt](int progress, int total) {
            int pct = (total > 0) ? (progress * 100 / total) : 0;
            // Only send log every 5% to avoid flooding WebSocket
            if (pct >= lastPct + 5 || pct == 100 || (pct == 0 && pct != lastPct)) {
                lastPct = pct;
                LT_IM(OTA, "OTA Progress: %d%%", pct);
                if (isMqtt) {
                    JsonDocument progResp;
                    progResp["cmd"] = "otaProgress";
                    progResp["status"] = "ok";
                    progResp["progress"] = progress;
                    progResp["total"] = total;
                    progResp["pct"] = pct;
                    _sendResponse(source, progResp);
                }
            }
        },
        [this, source](bool success, const char* msg) {
            LT_IM(OTA, "OTA upload %s: %s", success ? "success" : "failed", msg);
            JsonDocument resultResp;
            resultResp["cmd"] = "otaResult";
            resultResp["status"] = success ? "ok" : "error";
            resultResp["message"] = msg;
            _sendResponse(source, resultResp);
        });
    if (ok) {
        resp["status"] = "ok";
        resp["cmd"] = "beginUploadFirmwareSuccess";
        LT_IM(OTA, "stream started, size=%u", size);
    }
    else {
        resp["status"] = "error";
        resp["cmd"] = "beginUploadFirmwareFailed";
        resp["message"] = "OTA already running or update begin failed";
        LT_IM(OTA, "stream start failed");
    }
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareEnd(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    if (!_ota->isRunning()) {
        resp["status"] = "error";
        resp["message"] = "No OTA in progress";
        _sendResponse(source, resp);
        return;
    }
    _ota->end();
    LT_IM(OTA, "stream ended");
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareAbort(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    (void)resp;
    if (!_ota->isRunning()) {
        LT_IM(OTA, "abort requested but not running");
        return;
    }
    _ota->abort();
    LT_IM(OTA, "aborted");
}

template <typename T>
void CommandHandlerT<T>::_cmdOtaChunk(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (!_ota->isRunning()) {
        resp["status"] = "error";
        resp["message"] = "No OTA in progress";
        _sendResponse(source, resp);
        return;
    }
    String b64 = payload["data"].as<String>();
    if (b64.length() == 0) {
        resp["status"] = "error";
        resp["message"] = "Missing data";
        _sendResponse(source, resp);
        return;
    }
    size_t decodedMax = (b64.length() * 3) / 4 + 4;
    uint8_t* buf = new uint8_t[decodedMax];
    size_t olen = 0;
    if (!crypto::base64Decode(b64.c_str(), b64.length(), buf, decodedMax, &olen)) {
        delete[] buf;
        resp["status"] = "error";
        resp["message"] = "Base64 decode failed";
        _sendResponse(source, resp);
        return;
    }
    bool ok = _ota->writeChunk(buf, olen);
    delete[] buf;
    if (!ok) {
        resp["status"] = "error";
        resp["message"] = "Write chunk failed";
    }
    else {
        resp["status"] = "ok";
        resp["received"] = (unsigned long)olen;
    }
    //_sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetSystemInfo(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    resp["status"] = "ok";

    JsonVariantConst fields = payload["fields"];
    bool all = fields.isNull() || (fields.is<const char*>() && strcmp(fields.as<const char*>(), "all") == 0);

    auto has = [&](const char* name) -> bool {
        if (all) return true;
        for (auto f : fields.as<JsonArrayConst>()) {
            if (strcmp(f.as<const char*>(), name) == 0) return true;
        }
        return false;
        };

    if (has("system")) {
        JsonObject sys = resp["system"].to<JsonObject>();
        sys["chipId"] = chip::systemChipId();
        sys["chipModel"] = chip::chipModelName();
        sys["cpuFreq"] = ESP.getCpuFreqMHz();
        sys["sdkVersion"] = ESP.getSdkVersion();
        sys["buildTime"] = buildStr();
        sys["buildUnixTime"] = buildUnixTime();
        sys["uptime"] = millis() / 1000;

        time_t raw = _log->getEpoch();
        struct tm ti;
        gmtime_r(&raw, &ti);
        char buf[26];
        snprintf(buf, sizeof(buf), "%02d-%02d-%04d %02d:%02d:%02d", ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900, ti.tm_hour, ti.tm_min, ti.tm_sec);
        sys["timeSys"] = String(buf);
        sys["resetReason"] = chip::systemResetReason();
        
    }

    if (has("memory")) {
        JsonObject mem = resp["memory"].to<JsonObject>();
        mem["freeHeap"] = ESP.getFreeHeap();
        mem["minEverFreeHeap"] = (unsigned long)chip::heapMinFree();
        mem["maxAllocHeap"] = (unsigned long)chip::heapMaxAlloc();
    }

    if (has("tasks")) {
        JsonArray tasks = resp["tasks"].to<JsonArray>();
        UBaseType_t numTasks = uxTaskGetNumberOfTasks();
        TaskStatus_t* taskArray = (TaskStatus_t*)pvPortMalloc(numTasks * sizeof(TaskStatus_t));
        if (taskArray) {
            UBaseType_t count = uxTaskGetSystemState(taskArray, numTasks, nullptr);
            for (UBaseType_t i = 0; i < count; i++) {
                JsonObject t = tasks.add<JsonObject>();
                t["name"] = taskArray[i].pcTaskName;
                t["priority"] = taskArray[i].uxCurrentPriority;
                t["stackWaterMark"] = taskArray[i].usStackHighWaterMark;
                const char* stateStr = "other";
                switch (taskArray[i].eCurrentState) {
                case eRunning:   stateStr = "running"; break;
                case eReady:     stateStr = "ready"; break;
                case eBlocked:   stateStr = "blocked"; break;
                case eSuspended: stateStr = "suspended"; break;
                case eDeleted:   stateStr = "deleted"; break;
                default: break;
                }
                t["state"] = (const char*)stateStr;
            }
            vPortFree(taskArray);
        }
    }

    if (has("wifi")) {
        JsonObject w = resp["wifi"].to<JsonObject>();
        w["rssi"] = WiFi.RSSI();
        w["ssid"] = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPSSID() : WiFi.SSID();
        {
            IPAddress ip = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPIP() : WiFi.localIP();
            char ipBuf[16];
            snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", ip[0], ip[1], ip[2], ip[3]);
            w["ip"] = (const char*)ipBuf;
        }
        w["mac"] = WiFi.macAddress();
        w["channel"] = WiFi.channel();
        switch (g_connMode) {
        case ConnMode::AP_WS:     w["connMode"] = "ap_ws"; break;
        case ConnMode::STA_MQTT:  w["connMode"] = "sta_mqtt"; break;
        case ConnMode::DEBUG_WS:  w["connMode"] = "debug_ws"; break;
        }
        w["temperature"] = chip::readWifiTempC();
    }

    if (has("storage")) {
        JsonObject s = resp["storage"].to<JsonObject>();
        s["flashSize"] = ESP.getFlashChipSize();
        s["fsTotal"] = (unsigned long)compat::fsTotalBytes();
        s["fsUsed"] = (unsigned long)compat::fsUsedBytes();
        s["flashMode"] = chip::getFlashChipMode();
        s["flashSpeed"] = chip::getFlashChipSpeed();
    }

    if (has("pump")) {
        if (_driver) _driver->getSysInfo(resp);
    }

    _sendResponse(source, resp);

    if (payload["stream"].is<bool>() && payload["stream"].as<bool>()) {
        startStream(STREAM_SYSINFO, source, STREAM_DURATION_MS);
    }
}

template <typename T>
void CommandHandlerT<T>::_onScanDone() {
    _scanPending = false;

    _scanResultDoc.clear();
    _scanResultDoc["cmd"] = "scanWifi";

    int16_t count = compat::scanComplete();
    if (count >= 0) {
        chip::ScanResult results[40];
        int n = chip::scanGetResults(results, 40);
        JsonArray nets = _scanResultDoc["networks"].to<JsonArray>();
        for (int i = 0; i < n && i < count; i++) {
            JsonObject obj = nets.add<JsonObject>();
            obj["name"] = results[i].ssid;
            obj["rssi"] = results[i].rssi;
            char bssid[18];
            snprintf(bssid, sizeof(bssid), "%02X:%02X:%02X:%02X:%02X:%02X",
                results[i].bssid[0], results[i].bssid[1], results[i].bssid[2],
                results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);
            obj["bssid"] = bssid;
            obj["isEncrypt"] = results[i].isEncrypt;
        }
        _scanResultDoc["status"] = "ok";
    }
    else {
        _scanResultDoc["status"] = "error";
        _scanResultDoc["message"] = "Scan failed";
    }

    _scanResultReady = true;
    compat::scanDelete();

    JsonDocument notify;
    notify["cmd"] = "scanWifi";
    notify["status"] = "completed";
    _sendResponse(_scanSource, notify);
}

template <typename T>
void CommandHandlerT<T>::_cmdScanWifi(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;

    if (_scanPending) {
        // Watchdog: a scan that never completes must not block retries forever.
        if (millis() - _scanStartMs >= kScanTimeoutMs) {
            LT_IM(CMD, "Scan watchdog: previous scan timed out, allowing restart");
            _scanPending = false;
        } else {
            resp["status"] = "error";
            resp["message"] = "Scan already in progress";
            _sendResponse(source, resp);
            return;
        }
    }

    _scanPending = true;
    _scanStartMs = millis();
    _scanSource = source;

    LT_IM(CMD, "Starting async WiFi scan...");

    resp["status"] = "ok";
    resp["message"] = "Scan started";
    resp["wifiDrop"] = false;
    _sendResponse(source, resp);

    compat::scanAsync([this]() {
        this->_onScanDone();
    });
}

template <typename T>
void CommandHandlerT<T>::_cmdGetScanWifiData(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;

    if (_scanPending) {
        // Watchdog: report failure and release the slot so the app can retry.
        if (millis() - _scanStartMs >= kScanTimeoutMs) {
            LT_IM(CMD, "Scan watchdog: timeout while waiting for scan results");
            _scanPending = false;
            resp["status"] = "error";
            resp["message"] = "Scan failed";
            _sendResponse(source, resp);
            return;
        }
        resp["status"] = "error";
        resp["message"] = "Scan still in progress";
        _sendResponse(source, resp);
        return;
    }

    if (!_scanResultReady) {
        resp["status"] = "error";
        resp["message"] = "No scan data available";
        _sendResponse(source, resp);
        return;
    }

    _scanResultReady = false;

    // Merge kết quả scan vào resp (đã có cmd + reqId từ _handleCommand) —
    // app match response theo reqId, thiếu reqId → timeout 20s.
    for (JsonPair kv : _scanResultDoc.as<JsonObject>()) {
        resp[kv.key()] = kv.value();
    }
    _scanResultDoc.clear();

    _sendResponse(source, resp);
}

// ── Pairing: app chọn WiFi nhà gửi qua → lưu → reboot sang STA_MQTT ──
// Chỉ chấp nhận khi đang AP_WS (chặn từ MQTT/STA — bảo mật).
template <typename T>
void CommandHandlerT<T>::_cmdPair(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (g_connMode != ConnMode::AP_WS) {
        resp["status"] = "error";
        resp["message"] = "Pairing only allowed in AP mode";
        _sendResponse(source, resp);
        return;
    }

    const char* ssid = payload["wifiSsid"];
    if (!ssid || strlen(ssid) == 0) {
        resp["status"] = "error";
        resp["message"] = "Missing or invalid 'wifiSsid'";
        _sendResponse(source, resp);
        return;
    }

    if (!_identity) {
        resp["status"] = "error";
        resp["message"] = "Identity unavailable";
        _sendResponse(source, resp);
        return;
    }

    // controlKey do APP sinh — thiết bị lưu lại để phase 2 ký envelope.
    // getConfig KHÔNG trả controlKey nữa: ai nối AP open cũng chỉ đọc được
    // thông tin công khai (deviceId/apSSID), không lấy được khóa.
    const char* ck = payload["controlKey"].is<const char*>() ? payload["controlKey"].as<const char*>() : "";
    if (strlen(ck) == 0 || !_identity->setControlKeyHex(ck)) {
        resp["status"] = "error";
        resp["message"] = "Missing or invalid 'controlKey' (need 64 hex chars)";
        _sendResponse(source, resp);
        return;
    }
    _resetSeq(); // controlKey mới → chuỗi seq bắt đầu lại

    T& c = _cfg->get();
    strlcpy(c.wifiSSID, ssid, sizeof(c.wifiSSID));
    const char* pass = payload["wifiPass"].is<const char*>() ? payload["wifiPass"].as<const char*>() : "";
    strlcpy(c.wifiPass, pass, sizeof(c.wifiPass));

    // MQTT broker config
    if (payload["mqttServer"].is<const char*>() && strlen(payload["mqttServer"].as<const char*>()) > 0) {
        strlcpy(c.mqttServer, payload["mqttServer"].as<const char*>(), sizeof(c.mqttServer));
    }
    if (payload["mqttPort"].is<unsigned int>()) {
        c.mqttPort = payload["mqttPort"].as<unsigned int>();
    }
    if (payload["mqttUser"].is<const char*>() && strlen(payload["mqttUser"].as<const char*>()) > 0) {
        strlcpy(c.mqttUser, payload["mqttUser"].as<const char*>(), sizeof(c.mqttUser));
    }
    if (payload["mqttPass"].is<const char*>() && strlen(payload["mqttPass"].as<const char*>()) > 0) {
        _cfg->setPassPlain(payload["mqttPass"].as<const char*>());
    }

    c.connMode = ConnMode::STA_MQTT;
    _cfg->save(c);

    resp["status"] = "ok";
    resp["message"] = "Pairing saved. Rebooting...";
    resp["deviceId"] = _identity ? _identity->deviceId() : "";
    resp["pairingState"] = (_identity && _identity->isProvisioned()) ? "provisioned" : "unprovisioned";
    _sendResponse(source, resp);

    LT_IM(CMD, "Pairing: ssid=%s connMode=STA_MQTT, rebooting", ssid);
    delay(1200);
    ESP.restart();
}

// ── Provision (factory): inject/đổi controlKey ──
// Chỉ chấp nhận khi đang AP_WS (proximity). Hex 64 ký tự; bỏ trống = giữ nguyên.
template <typename T>
void CommandHandlerT<T>::_cmdProvision(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (g_connMode != ConnMode::AP_WS) {
        resp["status"] = "error";
        resp["message"] = "Provision only allowed in AP mode";
        _sendResponse(source, resp);
        return;
    }
    if (!_identity) {
        resp["status"] = "error";
        resp["message"] = "Identity unavailable";
        _sendResponse(source, resp);
        return;
    }

    if (payload["controlKey"].is<const char*>()) {
        if (!_identity->setControlKeyHex(payload["controlKey"].as<const char*>())) {
            resp["status"] = "error";
            resp["message"] = "Invalid controlKey (need 64 hex chars)";
            _sendResponse(source, resp);
            return;
        }
        _resetSeq(); // controlKey đổi → chuỗi seq bắt đầu lại
    }

    resp["status"] = "ok";
    resp["deviceId"] = _identity->deviceId();
    resp["pairingState"] = _identity->isProvisioned() ? "provisioned" : "unprovisioned";
    String ck;
    if (_identity->controlKeyHex(ck)) resp["controlKey"] = ck;
    LT_IM(CMD, "Provision: deviceId=%s state=%s", _identity->deviceId(), _identity->isProvisioned() ? "provisioned" : "unprovisioned");
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_handleFileCommand(const String& source, const String& cmd, const JsonDocument& payload, const String& reqId) {    String path = payload["path"] | String("/");

    String json;
    if (cmd == "listDir") {
        size_t offset = payload["offset"] | (unsigned int)0;
        size_t limit = payload["limit"] | (unsigned int)0;
        json = FileBrowser::listDir(path, offset, limit);
    }
    else if (cmd == "fileInfo") {
        json = FileBrowser::fileInfo(path);
    }
    else if (cmd == "deleteItem") {
        json = FileBrowser::deleteItem(path);
    }
    else if (cmd == "fsInfo") {
        json = FileBrowser::fsInfo();
    }
    else if (cmd == "downloadFile" || cmd == "readFile") {
        size_t offset = payload["offset"] | (unsigned int)0;
        size_t limit = payload["limit"] | (unsigned int)(cmd == "downloadFile" ? 1024 : 4096);
        bool encode = payload["encode"] | (cmd == "downloadFile");
        json = FileBrowser::readFile(path, offset, limit, encode);
    }

    if (json.length() > 1 && json[0] == '{') {
        json = "{\"cmd\":\"" + cmd + "\"" + (reqId.length() > 0 ? ",\"reqId\":\"" + reqId + "\"" : "") + "," + json.substring(1);
    }
    _sendResponse(source, json);
}
