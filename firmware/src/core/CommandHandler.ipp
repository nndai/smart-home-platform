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
        resp[F("status")] = F("error");
        resp[F("message")] = F("Invalid JSON");
        _sendResponse(source, resp);
        return;
    }

    const char* cmd = doc[F("cmd")];
    JsonVariant payloadVar = doc[F("payload")];

    if (!cmd) {
        JsonDocument resp;
        resp[F("status")] = F("error");
        resp[F("message")] = F("Missing cmd");
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

    if (!cmd[F("seq")].is<uint32_t>() || !cmd[F("ts")].is<uint32_t>() || !cmd[F("hmac")].is<const char*>()) {
        LT_EM(CMD, "Envelope: missing seq/ts/hmac");
        return false;
    }
    const uint32_t seq = cmd[F("seq")].as<uint32_t>();
    const uint32_t ts = cmd[F("ts")].as<uint32_t>();
    const char* hmacHex = cmd[F("hmac")].as<const char*>();

    if (strlen(hmacHex) != 64) {
        LT_EM(CMD, "Envelope: bad hmac length");
        return false;
    }

    // Nhiều controller (app, remote switch...) ký cùng controlKey nhưng giữ seq
    // riêng → floor riêng cho từng sender ("" = legacy sender thiếu src).
    const char* src = cmd[F("src")] | "";

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
        LT_EM(CMD, "Envelope: hmac mismatch (cmd=%s)", cmd[F("cmd")].as<const char*>());
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
    String cmdStr = cmd[F("cmd")].as<String>();
    String reqId = cmd[F("reqId")].is<String>() ? cmd[F("reqId")].as<String>() : "";

    // ── Lệnh từ MQTT PHẢI có envelope hợp lệ (seq/ts/hmac) — docs §3.2 ──
    // Chặn: giả mạo (kẻ khác publish lệnh), replay (seq cũ), lệch giờ (ts).
    // AP/WS (pairing) không cần envelope: proximity + WPA2.
    if (source == F("mqtt") && !_verifyEnvelope(cmd, payload)) {
        LT_EM(CMD, "Mqtt command '%s' rejected: invalid envelope", cmdStr.c_str());
        return;
    }

    JsonDocument resp;
    resp[F("cmd")] = cmdStr;
    
    if (reqId.length() > 0) resp[F("reqId")] = reqId;

    // Command riêng của thiết bị → chuyển cho driver
    if (_driver && _driver->handleCmd(cmdStr.c_str(), payload, resp)) {
        _sendResponse(source, resp);
        return;
    }

    if (cmdStr == F("getStatus")) _cmdGetStatus(source, payload, resp);
    else if (cmdStr == F("getConfig")) _cmdGetConfig(source, payload, resp);
    else if (cmdStr == F("setConfig")) _cmdSetConfig(source, payload, resp);
    else if (cmdStr == F("getLog")) _cmdGetLog(source, payload, resp);
    else if (cmdStr == F("clearSysLog")) _cmdClearSysLog(source, payload, resp);
    else if (cmdStr == F("otaUrl")) _cmdOtaUrl(source, payload, resp);
    else if (cmdStr == F("reboot")) _cmdReboot(source, payload, resp);
    else if (cmdStr == F("factoryReset")) _cmdFactoryReset(source, payload, resp);
    else if (cmdStr == F("setLogMqtt")) _cmdSetLogMqtt(source, payload, resp);
    else if (cmdStr == F("getLogMqtt")) _cmdGetLogMqtt(source, payload, resp);
    else if (cmdStr == F("getLogStats")) _cmdGetLogStats(source, payload, resp);
    else if (cmdStr == F("getSystemInfo")) _cmdGetSystemInfo(source, payload, resp);
    else if (cmdStr == F("uploadFirmwareStart")) _cmdUploadFirmwareStart(source, payload, resp);
    else if (cmdStr == F("uploadFirmwareEnd")) _cmdUploadFirmwareEnd(source, payload, resp);
    else if (cmdStr == F("uploadFirmwareAbort")) _cmdUploadFirmwareAbort(source, payload, resp);
    else if (cmdStr == F("otaChunk")) _cmdOtaChunk(source, payload, resp);
    else if (cmdStr == F("scanWifi")) _cmdScanWifi(source, payload, resp);
    else if (cmdStr == F("getScanWifiData")) _cmdGetScanWifiData(source, payload, resp);
    else if (cmdStr == F("pair")) _cmdPair(source, payload, resp);
    else if (cmdStr == F("provision")) _cmdProvision(source, payload, resp);
    else if (cmdStr == F("listDir") || cmdStr == F("readFile") || cmdStr == F("fileInfo") || cmdStr == F("deleteItem") || cmdStr == F("fsInfo") || cmdStr == F("downloadFile")) {
        _handleFileCommand(source, cmdStr, payload, reqId);
        return;
    }
    else {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Unknown command");
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
        resp[F("cmd")] = F("getStatus");
        _cmdGetStatus(source, emptyPayload, resp);
        break;
    case STREAM_SYSINFO:
        resp[F("cmd")] = F("getSystemInfo");
        emptyPayload[F("fields")] = F("all");
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
    resp[F("cmd")] = F("getStatus");
    _cmdGetStatus(F("mqtt"), emptyPayload, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetStatus(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    
    resp[F("timestamp")] = _log->getEpoch();

    resp[F("status")] = F("ok");
    resp[F("rssi")] = WiFi.RSSI();
    if (_driver) _driver->getStatus(resp);

    _sendResponse(source, resp);

    if (payload[F("stream")].is<bool>() && payload[F("stream")].as<bool>()) {
        startStream(STREAM_STATUS, source, STREAM_DURATION_MS);
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdGetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    T& c = _cfg->get();
    resp[F("status")] = F("ok");

    // Connection mode
    resp[F("connMode")] = (int)c.connMode;

    // MQTT — topic là "devices/{deviceId}" (chuẩn phase 2, không cấu hình tay)
    resp[F("mqttServer")] = c.mqttServer;
    resp[F("mqttPort")] = c.mqttPort;
    resp[F("mqttUser")] = c.mqttUser;
    resp[F("mqttPass")] = strlen(_cfg->passPlain()) > 0 ? F("********") : F("");
    if (_identity) {
        resp[F("mqttTopic")] = String(F("devices/")) + _identity->deviceId();
    } else {
        resp[F("mqttTopic")] = F("");
    }

    // WiFi
    resp[F("wifiSSID")] = c.wifiSSID;
    resp[F("wifiPass")] = strlen(c.wifiPass) > 0 ? F("********") : F("");
    resp[F("debugSSID")] = c.debugSSID;
    resp[F("debugPass")] = strlen(c.debugPass) > 0 ? F("********") : F("");

    // Danh tính + pairing (AP SSID tự suy ra từ deviceId — không cấu hình tay)
    if (_identity) {
        resp[F("deviceId")] = _identity->deviceId();
        resp[F("apSSID")] = _identity->apSSID();
    } else {
        resp[F("deviceId")] = F("");
        resp[F("apSSID")] = F("");
    }
    resp[F("profile")] = _profile;
    resp[F("pairingState")] = (_identity && _identity->isProvisioned()) ? F("provisioned") : F("unprovisioned");

    // Debug network settings
    char ipBuf[16];
    snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), c.debugIp[0], c.debugIp[1], c.debugIp[2], c.debugIp[3]);
    resp[F("debugIp")] = (const char*)ipBuf;
    snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), c.debugGateway[0], c.debugGateway[1], c.debugGateway[2], c.debugGateway[3]);
    resp[F("debugGateway")] = (const char*)ipBuf;
    snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), c.debugNetmask[0], c.debugNetmask[1], c.debugNetmask[2], c.debugNetmask[3]);
    resp[F("debugNetmask")] = (const char*)ipBuf;

    resp[F("sysLogFileEnabled")] = c.sysLogFileEnabled;
    resp[F("sysLogFileLevel")] = c.sysLogFileLevel;

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
    if (payload[F("connMode")].is<unsigned int>()) {
        int v = payload[F("connMode")].as<int>();
        if (v >= 0 && v <= 2) {
            c.connMode = (ConnMode)v;
            changed = true;
            needReboot = true;
        }
    }

    // MQTT settings
    if (payload[F("mqttServer")].is<const char*>()) {
        strlcpy(c.mqttServer, payload[F("mqttServer")], sizeof(c.mqttServer));
        changed = true;
        needReboot = true;
    }
    if (payload[F("mqttPort")].is<unsigned int>()) { 
        c.mqttPort = payload[F("mqttPort")]; 
        changed = true;
        needReboot = true;
    }
    if (payload[F("mqttUser")].is<const char*>()) {
        strlcpy(c.mqttUser, payload[F("mqttUser")], sizeof(c.mqttUser));
        changed = true;
        needReboot = true;
    }
    if (payload[F("mqttPass")].is<const char*>()) { 
        _cfg->setPassPlain(payload[F("mqttPass")].as<const char*>());
        changed = true; 
        needReboot = true;
    }
    // mqttTopic KHÔNG cấu hình tay nữa — topic chuẩn "devices/{deviceId}" (xem getConfig).

    // WiFi STA settings
    if (payload[F("wifiSSID")].is<const char*>()) { 
        strlcpy(c.wifiSSID, payload[F("wifiSSID")], sizeof(c.wifiSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload[F("wifiPass")].is<const char*>()) { 
        strlcpy(c.wifiPass, payload[F("wifiPass")], sizeof(c.wifiPass)); 
        changed = true; 
        needReboot = true;
    }

    // WiFi DEBUG settings
    if (payload[F("debugSSID")].is<const char*>()) { 
        strlcpy(c.debugSSID, payload[F("debugSSID")], sizeof(c.debugSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload[F("debugPass")].is<const char*>()) { 
        strlcpy(c.debugPass, payload[F("debugPass")], sizeof(c.debugPass)); 
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
    if (payload[F("debugIp")].is<const char*>()) { 
        changed |= parseIP(payload[F("debugIp")], c.debugIp); 
        needReboot = true;
    }
    if (payload[F("debugGateway")].is<const char*>()) { 
        changed |= parseIP(payload[F("debugGateway")], c.debugGateway); 
        needReboot = true;
    }
    if (payload[F("debugNetmask")].is<const char*>()) { 
        changed |= parseIP(payload[F("debugNetmask")], c.debugNetmask); 
        needReboot = true; 
    }

    // System log settings
    if (payload[F("sysLogFileEnabled")].is<bool>()) {
        c.sysLogFileEnabled = payload[F("sysLogFileEnabled")];
        _log->setSysLogFileEnabled(c.sysLogFileEnabled);
        changed = true;
    }
    if (payload[F("sysLogFileLevel")].is<unsigned int>()) {
        c.sysLogFileLevel = payload[F("sysLogFileLevel")];
        _log->setSysLogFileLevel(c.sysLogFileLevel);
        changed = true;
    }

    if (changed) {
        _cfg->save(c);
        resp[F("status")] = F("ok");
        resp[F("message")] = needReboot ? F("Config saved. Reboot required.") : F("Config saved.");
        resp[F("needReboot")] = needReboot;
        LT_IM(CMD, "Config updated%s", needReboot ? " (reboot needed)" : "");
    }
    else {
        resp[F("status")] = F("ok");
        resp[F("message")] = F("No changes");
    }

    _sendResponse(source, resp);
}


template <typename T>
void CommandHandlerT<T>::_cmdGetLog(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    String logContent;
    if (_log->readSysLog(logContent, 4096)) {
        resp[F("status")] = F("ok");
        resp[F("log")] = logContent;
        resp[F("logSize")] = _log->getSysLogSize();
    }
    else {
        resp[F("status")] = F("error");
        resp[F("message")] = F("No log available");
    }
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdClearSysLog(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _log->clearSysLog();
    resp[F("status")] = F("ok");
    resp[F("message")] = F("Sys log cleared");
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdOtaUrl(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    const char* url = payload[F("url")];
    if (!url) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Missing URL");
        _sendResponse(source, resp);
        return;
    }
    resp[F("status")] = F("ok");
    resp[F("message")] = F("OTA started from URL");
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
    resp[F("status")] = F("ok");
    resp[F("message")] = F("Rebooting...");
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
    resp[F("status")] = F("ok");
    resp[F("message")] = F("Factory reset. Rebooting...");
    _sendResponse(source, resp);
    LT_IM(CMD, "Factory reset (config + identity)");
    delay(1000);
    ESP.restart();
}

template <typename T>
void CommandHandlerT<T>::_cmdSetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    bool en = payload[F("enabled")].as<bool>();
    _log->setMqttLogEnabled(en);
    resp[F("status")] = F("ok");
    resp[F("enabled")] = en;
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp[F("status")] = F("ok");
    resp[F("enabled")] = _log->isMqttLogEnabled();
    _sendResponse(source, resp);
}


template <typename T>
void CommandHandlerT<T>::_cmdGetLogStats(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp[F("status")] = F("ok");
    resp[F("sysLogSize")] = _log->getSysLogSize();
    resp[F("toggleLogSize")] = _log->getToggleLogSize();
    resp[F("powerLogSize")] = _log->getPowerLogSize();
    resp[F("totalBytes")] = _log->getTotalBytes();
    resp[F("usedBytes")] = _log->getUsedBytes();
    resp[F("timeSynced")] = _log->isTimeSynced();
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareStart(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (!payload[F("size")].is<unsigned int>()) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Missing size");
        _sendResponse(source, resp);
        return;
    }
    size_t size = payload[F("size")].as<unsigned int>();
    bool isMqtt = (source == F("mqtt"));

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
                    progResp[F("cmd")] = F("otaProgress");
                    progResp[F("status")] = F("ok");
                    progResp[F("progress")] = progress;
                    progResp[F("total")] = total;
                    progResp[F("pct")] = pct;
                    _sendResponse(source, progResp);
                }
            }
        },
        [this, source](bool success, const char* msg) {
            LT_IM(OTA, "OTA upload %s: %s", success ? "success" : "failed", msg);
            JsonDocument resultResp;
            resultResp[F("cmd")] = F("otaResult");
            resultResp[F("status")] = success ? F("ok") : F("error");
            resultResp[F("message")] = msg;
            _sendResponse(source, resultResp);
        });
    if (ok) {
        resp[F("status")] = F("ok");
        resp[F("cmd")] = F("beginUploadFirmwareSuccess");
        LT_IM(OTA, "stream started, size=%u", size);
    }
    else {
        resp[F("status")] = F("error");
        resp[F("cmd")] = F("beginUploadFirmwareFailed");
        resp[F("message")] = F("OTA already running or update begin failed");
        LT_IM(OTA, "stream start failed");
    }
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareEnd(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    if (!_ota->isRunning()) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("No OTA in progress");
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
        resp[F("status")] = F("error");
        resp[F("message")] = F("No OTA in progress");
        _sendResponse(source, resp);
        return;
    }
    const char* b64 = payload[F("data")] | "";
    size_t b64Len = strlen(b64);
    if (b64Len == 0) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Missing data");
        _sendResponse(source, resp);
        return;
    }
    size_t decodedMax = (b64Len * 3) / 4 + 4;
    uint8_t* buf = new uint8_t[decodedMax];
    size_t olen = 0;
    if (!crypto::base64Decode(b64, b64Len, buf, decodedMax, &olen)) {
        delete[] buf;
        resp[F("status")] = F("error");
        resp[F("message")] = F("Base64 decode failed");
        _sendResponse(source, resp);
        return;
    }
    bool ok = _ota->writeChunk(buf, olen);
    delete[] buf;
    if (!ok) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Write chunk failed");
    }
    else {
        resp[F("status")] = F("ok");
        resp[F("received")] = (unsigned long)olen;
    }
    //_sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetSystemInfo(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    resp[F("status")] = F("ok");

    JsonVariantConst fields = payload[F("fields")];
    bool all = fields.isNull() || (fields.is<const char*>() && strcmp(fields.as<const char*>(), "all") == 0);

    auto has = [&](const __FlashStringHelper* name) -> bool {
        if (all) return true;
        for (auto f : fields.as<JsonArrayConst>()) {
            if (f.is<const char*>() && strcmp_P(f.as<const char*>(), (PGM_P)name) == 0) return true;
        }
        return false;
    };

    if (has(F("system"))) {
        JsonObject sys = resp[F("system")].to<JsonObject>();
        sys[F("chipId")] = chip::systemChipId();
        sys[F("chipModel")] = chip::chipModelName();
        sys[F("cpuFreq")] = ESP.getCpuFreqMHz();
        sys[F("sdkVersion")] = ESP.getSdkVersion();
        sys[F("buildTime")] = buildStr();
        sys[F("buildUnixTime")] = buildUnixTime();
        sys[F("uptime")] = millis() / 1000;

        time_t raw = _log->getEpoch();
        struct tm ti;
        gmtime_r(&raw, &ti);
        char buf[26];
        snprintf_P(buf, sizeof(buf), PSTR("%02d-%02d-%04d %02d:%02d:%02d"), ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900, ti.tm_hour, ti.tm_min, ti.tm_sec);
        sys[F("timeSys")] = String(buf);
        sys[F("resetReason")] = chip::systemResetReason();
        
    }

    if (has(F("memory"))) {
        JsonObject mem = resp[F("memory")].to<JsonObject>();
        mem[F("freeHeap")] = ESP.getFreeHeap();
        mem[F("minEverFreeHeap")] = (unsigned long)chip::heapMinFree();
        mem[F("maxAllocHeap")] = (unsigned long)chip::heapMaxAlloc();
    }

    if (has(F("tasks"))) {
        JsonArray tasks = resp[F("tasks")].to<JsonArray>();
        UBaseType_t numTasks = uxTaskGetNumberOfTasks();
        TaskStatus_t* taskArray = (TaskStatus_t*)pvPortMalloc(numTasks * sizeof(TaskStatus_t));
        if (taskArray) {
            UBaseType_t count = uxTaskGetSystemState(taskArray, numTasks, nullptr);
            for (UBaseType_t i = 0; i < count; i++) {
                JsonObject t = tasks.add<JsonObject>();
                t[F("name")] = taskArray[i].pcTaskName;
                t[F("priority")] = taskArray[i].uxCurrentPriority;
                t[F("stackWaterMark")] = taskArray[i].usStackHighWaterMark;
                switch (taskArray[i].eCurrentState) {
                case eRunning:   t[F("state")] = F("running"); break;
                case eReady:     t[F("state")] = F("ready"); break;
                case eBlocked:   t[F("state")] = F("blocked"); break;
                case eSuspended: t[F("state")] = F("suspended"); break;
                case eDeleted:   t[F("state")] = F("deleted"); break;
                default:         t[F("state")] = F("other"); break;
                }
            }
            vPortFree(taskArray);
        }
    }

    if (has(F("wifi"))) {
        JsonObject w = resp[F("wifi")].to<JsonObject>();
        w[F("rssi")] = WiFi.RSSI();
        w[F("ssid")] = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPSSID() : WiFi.SSID();
        {
            IPAddress ip = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPIP() : WiFi.localIP();
            char ipBuf[16];
            snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), ip[0], ip[1], ip[2], ip[3]);
            w[F("ip")] = (const char*)ipBuf;
        }
        w[F("mac")] = WiFi.macAddress();
        w[F("channel")] = WiFi.channel();
        switch (g_connMode) {
        case ConnMode::AP_WS:     w[F("connMode")] = F("ap_ws"); break;
        case ConnMode::STA_MQTT:  w[F("connMode")] = F("sta_mqtt"); break;
        case ConnMode::DEBUG_WS:  w[F("connMode")] = F("debug_ws"); break;
        }
        w[F("temperature")] = chip::readWifiTempC();
    }

    if (has(F("storage"))) {
        JsonObject s = resp[F("storage")].to<JsonObject>();
        s[F("flashSize")] = ESP.getFlashChipSize();
        s[F("fsTotal")] = (unsigned long)compat::fsTotalBytes();
        s[F("fsUsed")] = (unsigned long)compat::fsUsedBytes();
        s[F("flashMode")] = chip::getFlashChipMode();
        s[F("flashSpeed")] = chip::getFlashChipSpeed();
    }

    if (has(F("pump"))) {
        if (_driver) _driver->getSysInfo(resp);
    }

    _sendResponse(source, resp);

    if (payload[F("stream")].is<bool>() && payload[F("stream")].as<bool>()) {
        startStream(STREAM_SYSINFO, source, STREAM_DURATION_MS);
    }
}

template <typename T>
void CommandHandlerT<T>::_onScanDone() {
    _scanPending = false;

    _scanResultDoc.clear();
    _scanResultDoc[F("cmd")] = F("scanWifi");

    int16_t count = compat::scanComplete();
    if (count >= 0) {
        chip::ScanResult results[40];
        int n = chip::scanGetResults(results, 40);
        JsonArray nets = _scanResultDoc[F("networks")].to<JsonArray>();
        for (int i = 0; i < n && i < count; i++) {
            JsonObject obj = nets.add<JsonObject>();
            obj[F("name")] = results[i].ssid;
            obj[F("rssi")] = results[i].rssi;
            char bssid[18];
            snprintf_P(bssid, sizeof(bssid), PSTR("%02X:%02X:%02X:%02X:%02X:%02X"),
                results[i].bssid[0], results[i].bssid[1], results[i].bssid[2],
                results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);
            obj[F("bssid")] = bssid;
            obj[F("isEncrypt")] = results[i].isEncrypt;
        }
        _scanResultDoc[F("status")] = F("ok");
    }
    else {
        _scanResultDoc[F("status")] = F("error");
        _scanResultDoc[F("message")] = F("Scan failed");
    }

    _scanResultReady = true;
    compat::scanDelete();

    JsonDocument notify;
    notify[F("cmd")] = F("scanWifi");
    notify[F("status")] = F("completed");
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
            resp[F("status")] = F("error");
            resp[F("message")] = F("Scan already in progress");
            _sendResponse(source, resp);
            return;
        }
    }

    _scanPending = true;
    _scanStartMs = millis();
    _scanSource = source;

    LT_IM(CMD, "Starting async WiFi scan...");

    resp[F("status")] = F("ok");
    resp[F("message")] = F("Scan started");
    resp[F("wifiDrop")] = false;
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
            resp[F("status")] = F("error");
            resp[F("message")] = F("Scan failed");
            _sendResponse(source, resp);
            return;
        }
        resp[F("status")] = F("error");
        resp[F("message")] = F("Scan still in progress");
        _sendResponse(source, resp);
        return;
    }

    if (!_scanResultReady) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("No scan data available");
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
        resp[F("status")] = F("error");
        resp[F("message")] = F("Pairing only allowed in AP mode");
        _sendResponse(source, resp);
        return;
    }

    const char* ssid = payload[F("wifiSsid")];
    if (!ssid || strlen(ssid) == 0) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Missing or invalid 'wifiSsid'");
        _sendResponse(source, resp);
        return;
    }

    if (!_identity) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Identity unavailable");
        _sendResponse(source, resp);
        return;
    }

    // controlKey do APP sinh — thiết bị lưu lại để phase 2 ký envelope.
    // getConfig KHÔNG trả controlKey nữa: ai nối AP open cũng chỉ đọc được
    // thông tin công khai (deviceId/apSSID), không lấy được khóa.
    const char* ck = payload[F("controlKey")].is<const char*>() ? payload[F("controlKey")].as<const char*>() : "";
    if (strlen(ck) == 0 || !_identity->setControlKeyHex(ck)) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Missing or invalid 'controlKey' (need 64 hex chars)");
        _sendResponse(source, resp);
        return;
    }
    _resetSeq(); // controlKey mới → chuỗi seq bắt đầu lại

    T& c = _cfg->get();
    strlcpy(c.wifiSSID, ssid, sizeof(c.wifiSSID));
    const char* pass = payload[F("wifiPass")].is<const char*>() ? payload[F("wifiPass")].as<const char*>() : "";
    strlcpy(c.wifiPass, pass, sizeof(c.wifiPass));

    // MQTT broker config
    if (payload[F("mqttServer")].is<const char*>() && strlen(payload[F("mqttServer")].as<const char*>()) > 0) {
        strlcpy(c.mqttServer, payload[F("mqttServer")].as<const char*>(), sizeof(c.mqttServer));
    }
    if (payload[F("mqttPort")].is<unsigned int>()) {
        c.mqttPort = payload[F("mqttPort")].as<unsigned int>();
    }
    if (payload[F("mqttUser")].is<const char*>() && strlen(payload[F("mqttUser")].as<const char*>()) > 0) {
        strlcpy(c.mqttUser, payload[F("mqttUser")].as<const char*>(), sizeof(c.mqttUser));
    }
    if (payload[F("mqttPass")].is<const char*>() && strlen(payload[F("mqttPass")].as<const char*>()) > 0) {
        _cfg->setPassPlain(payload[F("mqttPass")].as<const char*>());
    }

    c.connMode = ConnMode::STA_MQTT;
    _cfg->save(c);

    resp[F("status")] = F("ok");
    resp[F("message")] = F("Pairing saved. Rebooting...");
    if (_identity) {
        resp[F("deviceId")] = _identity->deviceId();
    } else {
        resp[F("deviceId")] = F("");
    }
    resp[F("pairingState")] = (_identity && _identity->isProvisioned()) ? F("provisioned") : F("unprovisioned");
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
        resp[F("status")] = F("error");
        resp[F("message")] = F("Provision only allowed in AP mode");
        _sendResponse(source, resp);
        return;
    }
    if (!_identity) {
        resp[F("status")] = F("error");
        resp[F("message")] = F("Identity unavailable");
        _sendResponse(source, resp);
        return;
    }

    if (payload[F("controlKey")].is<const char*>()) {
        if (!_identity->setControlKeyHex(payload[F("controlKey")].as<const char*>())) {
            resp[F("status")] = F("error");
            resp[F("message")] = F("Invalid controlKey (need 64 hex chars)");
            _sendResponse(source, resp);
            return;
        }
        _resetSeq(); // controlKey đổi → chuỗi seq bắt đầu lại
    }

    resp[F("status")] = F("ok");
    if (_identity) {
        resp[F("deviceId")] = _identity->deviceId();
    } else {
        resp[F("deviceId")] = F("");
    }
    resp[F("pairingState")] = (_identity && _identity->isProvisioned()) ? F("provisioned") : F("unprovisioned");
    String ck;
    if (_identity->controlKeyHex(ck)) resp[F("controlKey")] = ck;
    LT_IM(CMD, "Provision: deviceId=%s state=%s", _identity->deviceId(), _identity->isProvisioned() ? "provisioned" : "unprovisioned");
    _sendResponse(source, resp);
}

template <typename T>
void CommandHandlerT<T>::_handleFileCommand(const String& source, const String& cmd, const JsonDocument& payload, const String& reqId) {
    String path = payload[F("path")] | String(F("/"));

    String json;
    if (cmd == F("listDir")) {
        size_t offset = payload[F("offset")] | (unsigned int)0;
        size_t limit = payload[F("limit")] | (unsigned int)0;
        json = FileBrowser::listDir(path, offset, limit);
    }
    else if (cmd == F("fileInfo")) {
        json = FileBrowser::fileInfo(path);
    }
    else if (cmd == F("deleteItem")) {
        json = FileBrowser::deleteItem(path);
    }
    else if (cmd == F("fsInfo")) {
        json = FileBrowser::fsInfo();
    }
    else if (cmd == F("downloadFile") || cmd == F("readFile")) {
        size_t offset = payload[F("offset")] | (unsigned int)0;
        size_t limit = payload[F("limit")] | (unsigned int)(cmd == F("downloadFile") ? 1024 : 4096);
        bool encode = payload[F("encode")] | (cmd == F("downloadFile"));
        json = FileBrowser::readFile(path, offset, limit, encode);
    }

    if (json.length() > 1 && json[0] == '{') {
        json = String(F("{\"cmd\":\"")) + cmd + F("\"") + (reqId.length() > 0 ? String(F(",\"reqId\":\"")) + reqId + F("\"") : String("")) + F(",") + json.substring(1);
    }
    _sendResponse(source, json);
}
