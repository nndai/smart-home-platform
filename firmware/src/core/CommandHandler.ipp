#include "core/CommandHandler.h"
#include <Arduino.h>
#include "compat/wifi.h"
#include "compat/wifi_scan.h"
#include "chip/io.h"
#include "chip/scan.h"
#include "core/Crypto.h"
#include "core/BuildInfo.h"
#include "core/DeviceIdentity.h"
#include "core/FileBrowser.h"
#include "protocol/BinaryProtocol.h"
#include "protocol/BinaryWriter.h"
#include "protocol/BinaryCommandIds.h"
#include "protocol/BinaryFieldIds.h"
#include "protocol/CommandContextBinary.h"

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
    _seqCount = 0;
}

template <typename T>
void CommandHandlerT<T>::setBinaryResponseCallback(BinaryResponseCallback cb) {
    _binaryResponseCb = cb;
}

template <typename T>
void CommandHandlerT<T>::_sendBinaryResponse(const String& source, const uint8_t* data, size_t length) {
    if (_binaryResponseCb) _binaryResponseCb(source, data, length);
}

// ── Envelope verify (docs §3.2) ──
template <typename T>
bool CommandHandlerT<T>::_verifyEnvelope(protocol::CommandId cmdId, const protocol::CommandRequest& req) {
    if (!_identity) {
        LT_EM(CMD, "Envelope: identity unavailable");
        return false;
    }

    uint32_t seq = 0;
    uint32_t ts = 0;
    String hmacHex;
    String src;

    if (!req.getUint(protocol::FieldId::Seq, seq) ||
        !req.getUint(protocol::FieldId::Ts, ts) ||
        !req.getString(protocol::FieldId::Hmac, hmacHex)) {
        LT_EM(CMD, "Envelope: missing seq/ts/hmac");
        return false;
    }

    if (hmacHex.length() != 64) {
        LT_EM(CMD, "Envelope: bad hmac length");
        return false;
    }

    req.getString(protocol::FieldId::Src, src);

    // Time drift check
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

    String keyHex;
    if (!_identity->controlKeyHex(keyHex)) {
        LT_EM(CMD, "Envelope: no controlKey");
        return false;
    }

    const char* cmdStr = protocol::commandIdToString(static_cast<uint8_t>(cmdId));
    const String canonical = crypto::buildCanonical(seq, ts, cmdStr, "", src.c_str());

    char expectedHex[65];
    if (!crypto::hmacSha256HexKey(keyHex.c_str(), canonical.c_str(), canonical.length(), expectedHex)) {
        LT_EM(CMD, "Envelope: hmac compute failed");
        return false;
    }
    if (strcmp(expectedHex, hmacHex.c_str()) != 0) {
        LT_EM(CMD, "Envelope: hmac mismatch (cmd=%s)", cmdStr);
        return false;
    }

    SeqEntry* entry = nullptr;
    for (uint8_t i = 0; i < _seqCount; i++) {
        if (strcmp(_seqTable[i].src, src.c_str()) == 0) {
            entry = &_seqTable[i];
            break;
        }
    }
    if (!entry) {
        if (_seqCount >= MAX_SEQ_ENTRIES) {
            LT_EM(CMD, "Envelope: too many senders (max %u)", (unsigned)MAX_SEQ_ENTRIES);
            return false;
        }
        if (src.length() >= sizeof(entry->src)) {
            LT_EM(CMD, "Envelope: src too long");
            return false;
        }
        entry = &_seqTable[_seqCount++];
        strlcpy(entry->src, src.c_str(), sizeof(entry->src));
        entry->seq = 0;
    }

    if (seq <= entry->seq) {
        LT_EM(CMD, "Envelope: stale seq %u (last %u) from '%s'", (unsigned)seq, (unsigned)entry->seq, src.c_str());
        return false;
    }

    entry->seq = seq;
    return true;
}

template <typename T>
void CommandHandlerT<T>::_resetSeq() {
    _seqCount = 0;
}

template <typename T>
void CommandHandlerT<T>::handleCommandBinary(const String& source, const uint8_t* payload, size_t length) {
    protocol::CommandId commandId = protocol::CommandId::Unknown;
    const uint8_t* payloadData = nullptr;
    size_t payloadLen = 0;

    if (!protocol::parseFrame(payload, length, commandId, payloadData, payloadLen)) {
        LT_EM(CMD, "Invalid binary frame");
        return;
    }

    protocol::BinaryCommandRequest req(payloadData, payloadLen);

    if (source == F("mqtt") && !_verifyEnvelope(commandId, req)) {
        LT_EM(CMD, "Mqtt binary cmd %u rejected: invalid envelope", static_cast<uint8_t>(commandId));
        return;
    }

    protocol::BinaryWriter writer(commandId);
    protocol::BinaryCommandResponse resp(&writer);

    String reqId;
    if (req.getString(protocol::FieldId::ReqId, reqId) && reqId.length() > 0) {
        resp.setString(protocol::FieldId::ReqId, reqId);
    }

    // Driver command handling
    String cmdStr = protocol::commandIdToString(static_cast<uint8_t>(commandId));
    if (_driver && _driver->handleCmd(cmdStr.c_str(), req, resp)) {
        _sendBinaryResponse(source, writer.data(), writer.size());
        return;
    }

    // System commands
    switch (commandId) {
        case protocol::CommandId::GetStatus:
            _cmdGetStatus(source, req, resp);
            break;
        case protocol::CommandId::GetConfig:
            _cmdGetConfig(source, req, resp);
            break;
        case protocol::CommandId::SetConfig:
            _cmdSetConfig(source, req, resp);
            break;
        case protocol::CommandId::GetLog:
            _cmdGetLog(source, req, resp);
            break;
        case protocol::CommandId::ClearSysLog:
            _cmdClearSysLog(source, req, resp);
            break;
        case protocol::CommandId::OtaUrl:
            _cmdOtaUrl(source, req, resp);
            break;
        case protocol::CommandId::Reboot:
            _cmdReboot(source, req, resp);
            break;
        case protocol::CommandId::FactoryReset:
            _cmdFactoryReset(source, req, resp);
            break;
        case protocol::CommandId::SetLogMqtt:
            _cmdSetLogMqtt(source, req, resp);
            break;
        case protocol::CommandId::GetLogMqtt:
            _cmdGetLogMqtt(source, req, resp);
            break;
        case protocol::CommandId::GetLogStats:
            _cmdGetLogStats(source, req, resp);
            break;
        case protocol::CommandId::GetSystemInfo:
            _cmdGetSystemInfo(source, req, resp);
            break;
        case protocol::CommandId::UploadFirmwareStart:
            _cmdUploadFirmwareStart(source, req, resp);
            break;
        case protocol::CommandId::UploadFirmwareEnd:
            _cmdUploadFirmwareEnd(source, req, resp);
            break;
        case protocol::CommandId::UploadFirmwareAbort:
            _cmdUploadFirmwareAbort(source, req, resp);
            break;
        case protocol::CommandId::OtaChunk:
            _cmdOtaChunk(source, req, resp);
            break;
        case protocol::CommandId::ScanWifi:
            _cmdScanWifi(source, req, resp);
            break;
        case protocol::CommandId::GetScanWifiData:
            if (_scanResultReady && _scanResultBuf && _scanResultLen > 0) {
                _scanResultReady = false;
                _sendBinaryResponse(source, _scanResultBuf, _scanResultLen);
                free(_scanResultBuf);
                _scanResultBuf = nullptr;
                _scanResultLen = 0;
                return;
            }
            _cmdGetScanWifiData(source, req, resp);
            break;
        case protocol::CommandId::Pair:
            _cmdPair(source, req, resp);
            break;
        case protocol::CommandId::Provision:
            _cmdProvision(source, req, resp);
            break;
        case protocol::CommandId::ListDir:
        case protocol::CommandId::ReadFile:
        case protocol::CommandId::FileInfo:
        case protocol::CommandId::DeleteItem:
        case protocol::CommandId::FsInfo:
        case protocol::CommandId::DownloadFile:
            _handleFileCommand(source, commandId, req, resp);
            break;
        default:
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Unknown command");
            break;
    }

    _sendBinaryResponse(source, writer.data(), writer.size());
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
    const String& source = _streamSource[type];

    protocol::CommandId cmdId = (type == STREAM_STATUS) ? protocol::CommandId::GetStatus : protocol::CommandId::GetSystemInfo;
    protocol::BinaryWriter writer(cmdId);
    protocol::BinaryCommandResponse resp(&writer);

    protocol::BinaryCommandRequest emptyReq(nullptr, 0);
    if (type == STREAM_STATUS) {
        _cmdGetStatus(source, emptyReq, resp);
    } else if (type == STREAM_SYSINFO) {
        _cmdGetSystemInfo(source, emptyReq, resp);
    }

    _sendBinaryResponse(source, writer.data(), writer.size());
}

template <typename T>
void CommandHandlerT<T>::publishStatusToUp() {
    protocol::BinaryWriter writer(protocol::CommandId::GetStatus);
    protocol::BinaryCommandResponse resp(&writer);

    protocol::BinaryCommandRequest emptyReq(nullptr, 0);
    _cmdGetStatus(F("mqtt"), emptyReq, resp);

    _sendBinaryResponse(F("mqtt"), writer.data(), writer.size());
}

template <typename T>
void CommandHandlerT<T>::_cmdGetStatus(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setU32(protocol::FieldId::Timestamp, _log ? _log->getEpoch() : 0);
    resp.setI32(protocol::FieldId::Rssi, WiFi.RSSI());

    if (_driver) {
        _driver->getStatus(resp);
    }

    bool stream = false;
    if (payload.getBool(protocol::FieldId::Stream, stream) && stream) {
        startStream(STREAM_STATUS, source, STREAM_DURATION_MS);
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdGetConfig(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)payload;
    (void)source;
    T& c = _cfg->get();
    resp.setString(protocol::FieldId::Status, "ok");

    resp.setI32(protocol::FieldId::ConnMode, (int)c.connMode);
    resp.setString(protocol::FieldId::MqttServer, c.mqttServer);
    resp.setU16(protocol::FieldId::MqttPort, c.mqttPort);
    resp.setString(protocol::FieldId::MqttUser, c.mqttUser);
    resp.setString(protocol::FieldId::MqttPass, strlen(_cfg->passPlain()) > 0 ? "********" : "");
    if (_identity) {
        resp.setString(protocol::FieldId::MqttTopic, String(F("devices/")) + _identity->deviceId());
        resp.setString(protocol::FieldId::DeviceId, _identity->deviceId());
        resp.setString(protocol::FieldId::ApSSID, _identity->apSSID());
        resp.setString(protocol::FieldId::PairingState, _identity->isProvisioned() ? "provisioned" : "unprovisioned");
    } else {
        resp.setString(protocol::FieldId::MqttTopic, "");
        resp.setString(protocol::FieldId::DeviceId, "");
        resp.setString(protocol::FieldId::ApSSID, "");
        resp.setString(protocol::FieldId::PairingState, "unprovisioned");
    }

    resp.setString(protocol::FieldId::WifiSSID, c.wifiSSID);
    resp.setString(protocol::FieldId::WifiPass, strlen(c.wifiPass) > 0 ? "********" : "");
    resp.setString(protocol::FieldId::DebugSSID, c.debugSSID);
    resp.setString(protocol::FieldId::DebugPass, strlen(c.debugPass) > 0 ? "********" : "");
    resp.setString(protocol::FieldId::Profile, _profile);

    char ipBuf[16];
    snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), c.debugIp[0], c.debugIp[1], c.debugIp[2], c.debugIp[3]);
    resp.setString(protocol::FieldId::DebugIp, ipBuf);
    snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), c.debugGateway[0], c.debugGateway[1], c.debugGateway[2], c.debugGateway[3]);
    resp.setString(protocol::FieldId::DebugGateway, ipBuf);
    snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), c.debugNetmask[0], c.debugNetmask[1], c.debugNetmask[2], c.debugNetmask[3]);
    resp.setString(protocol::FieldId::DebugNetmask, ipBuf);

    resp.setBool(protocol::FieldId::SysLogFileEnabled, c.sysLogFileEnabled);
    resp.setU8(protocol::FieldId::SysLogFileLevel, c.sysLogFileLevel);

    if (_driver) {
        _driver->getConfig(resp);
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdSetConfig(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    T& c = _cfg->get();
    bool changed = false;
    bool needReboot = false;

    int32_t v = 0;
    if (payload.getInt(protocol::FieldId::ConnMode, v)) {
        if (v >= 0 && v <= 2) {
            c.connMode = (ConnMode)v;
            changed = true;
            needReboot = true;
        }
    }

    String s;
    if (payload.getString(protocol::FieldId::MqttServer, s)) {
        strlcpy(c.mqttServer, s.c_str(), sizeof(c.mqttServer));
        changed = true;
        needReboot = true;
    }
    uint32_t u = 0;
    if (payload.getUint(protocol::FieldId::MqttPort, u)) {
        c.mqttPort = (uint16_t)u;
        changed = true;
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::MqttUser, s)) {
        strlcpy(c.mqttUser, s.c_str(), sizeof(c.mqttUser));
        changed = true;
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::MqttPass, s)) {
        _cfg->setPassPlain(s.c_str());
        changed = true;
        needReboot = true;
    }

    if (payload.getString(protocol::FieldId::WifiSSID, s)) {
        strlcpy(c.wifiSSID, s.c_str(), sizeof(c.wifiSSID));
        changed = true;
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::WifiPass, s)) {
        strlcpy(c.wifiPass, s.c_str(), sizeof(c.wifiPass));
        changed = true;
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::DebugSSID, s)) {
        strlcpy(c.debugSSID, s.c_str(), sizeof(c.debugSSID));
        changed = true;
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::DebugPass, s)) {
        strlcpy(c.debugPass, s.c_str(), sizeof(c.debugPass));
        changed = true;
        needReboot = true;
    }

    if (_driver) {
        if (_driver->setConfig(payload, resp)) {
            changed = true;
        }
    }

    auto parseIP = [](const char* str, uint8_t ip[4]) -> bool {
        return sscanf(str, "%hhu.%hhu.%hhu.%hhu", &ip[0], &ip[1], &ip[2], &ip[3]) == 4;
    };
    if (payload.getString(protocol::FieldId::DebugIp, s)) {
        changed |= parseIP(s.c_str(), c.debugIp);
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::DebugGateway, s)) {
        changed |= parseIP(s.c_str(), c.debugGateway);
        needReboot = true;
    }
    if (payload.getString(protocol::FieldId::DebugNetmask, s)) {
        changed |= parseIP(s.c_str(), c.debugNetmask);
        needReboot = true;
    }

    bool b = false;
    if (payload.getBool(protocol::FieldId::SysLogFileEnabled, b)) {
        c.sysLogFileEnabled = b;
        _log->setSysLogFileEnabled(c.sysLogFileEnabled);
        changed = true;
    }
    if (payload.getInt(protocol::FieldId::SysLogFileLevel, v)) {
        c.sysLogFileLevel = (uint8_t)v;
        _log->setSysLogFileLevel(c.sysLogFileLevel);
        changed = true;
    }

    if (changed) {
        _cfg->save(c);
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::Message, needReboot ? "Config saved. Reboot required." : "Config saved.");
        resp.setBool(protocol::FieldId::NeedReboot, needReboot);
        LT_IM(CMD, "Config updated%s", needReboot ? " (reboot needed)" : "");
    } else {
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::Message, "No changes");
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdGetLog(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    String logContent;
    if (_log->readSysLog(logContent, 4096)) {
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::Log, logContent);
        resp.setU32(protocol::FieldId::LogSize, (uint32_t)_log->getSysLogSize());
    } else {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "No log available");
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdClearSysLog(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    _log->clearSysLog();
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Message, "Sys log cleared");
}

template <typename T>
void CommandHandlerT<T>::_cmdOtaUrl(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    String url;
    if (!payload.getString(protocol::FieldId::Url, url) || url.length() == 0) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Missing URL");
        return;
    }

    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Message, "OTA started from URL");

    static int lastPct = -1;
    lastPct = -1;

    _ota->startFromUrl(url.c_str(),
        [this, source](int progress, int total) {
            int pct = (total > 0) ? (progress * 100 / total) : 0;
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
void CommandHandlerT<T>::_cmdReboot(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Message, "Rebooting...");
    LT_IM(CMD, "Rebooting...");
    delay(1000);
    ESP.restart();
}

template <typename T>
void CommandHandlerT<T>::_cmdFactoryReset(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    _cfg->reset();
    if (_identity) _identity->reset();
    _resetSeq();
    if (_scanResultBuf) {
        free(_scanResultBuf);
        _scanResultBuf = nullptr;
        _scanResultLen = 0;
    }
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Message, "Factory reset. Rebooting...");
    LT_IM(CMD, "Factory reset (config + identity)");
    delay(1000);
    ESP.restart();
}

template <typename T>
void CommandHandlerT<T>::_cmdSetLogMqtt(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    bool en = false;
    payload.getBool(protocol::FieldId::Enabled, en);
    _log->setMqttLogEnabled(en);
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setBool(protocol::FieldId::Enabled, en);
}

template <typename T>
void CommandHandlerT<T>::_cmdGetLogMqtt(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setBool(protocol::FieldId::Enabled, _log->isMqttLogEnabled());
}

template <typename T>
void CommandHandlerT<T>::_cmdGetLogStats(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setU32(protocol::FieldId::SysLogSize, (uint32_t)_log->getSysLogSize());
    resp.setU32(protocol::FieldId::ToggleLogSize, (uint32_t)_log->getToggleLogSize());
    resp.setU32(protocol::FieldId::PowerLogSize, (uint32_t)_log->getPowerLogSize());
    resp.setU32(protocol::FieldId::TotalBytes, (uint32_t)_log->getTotalBytes());
    resp.setU32(protocol::FieldId::UsedBytes, (uint32_t)_log->getUsedBytes());
    resp.setBool(protocol::FieldId::TimeSynced, _log->isTimeSynced());
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareStart(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    uint32_t size = 0;
    if (!payload.getUint(protocol::FieldId::Size, size) || size == 0) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Missing size");
        return;
    }
    bool isMqtt = (source == F("mqtt"));

    static int lastPct = -1;
    lastPct = -1;
    bool ok = _ota->startFromStream(size,
        [this, source, isMqtt](int progress, int total) {
            int pct = (total > 0) ? (progress * 100 / total) : 0;
            if (pct >= lastPct + 5 || pct == 100 || (pct == 0 && pct != lastPct)) {
                lastPct = pct;
                LT_IM(OTA, "OTA Progress: %d%%", pct);
                if (isMqtt) {
                    protocol::BinaryWriter progWriter(protocol::CommandId::OtaProgress);
                    progWriter.writeString(protocol::FieldId::Status, "ok", 2);
                    progWriter.writeU32(protocol::FieldId::Progress, (uint32_t)progress);
                    progWriter.writeU32(protocol::FieldId::Total, (uint32_t)total);
                    progWriter.writeI32(protocol::FieldId::Pct, pct);
                    _sendBinaryResponse(source, progWriter.data(), progWriter.size());
                }
            }
        },
        [this, source](bool success, const char* msg) {
            LT_IM(OTA, "OTA upload %s: %s", success ? "success" : "failed", msg);
            protocol::BinaryWriter resultWriter(protocol::CommandId::OtaResult);
            resultWriter.writeString(protocol::FieldId::Status, success ? "ok" : "error", success ? 2 : 5);
            resultWriter.writeString(protocol::FieldId::Message, msg, strlen(msg));
            _sendBinaryResponse(source, resultWriter.data(), resultWriter.size());
        });
    if (ok) {
        resp.setString(protocol::FieldId::Status, "ok");
        LT_IM(OTA, "stream started, size=%u", size);
    } else {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "OTA already running or update begin failed");
        LT_IM(OTA, "stream start failed");
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareEnd(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    if (!_ota->isRunning()) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "No OTA in progress");
        return;
    }
    _ota->end();
    resp.setString(protocol::FieldId::Status, "ok");
    LT_IM(OTA, "stream ended");
}

template <typename T>
void CommandHandlerT<T>::_cmdUploadFirmwareAbort(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;
    if (!_ota->isRunning()) {
        LT_IM(OTA, "abort requested but not running");
        return;
    }
    _ota->abort();
    resp.setString(protocol::FieldId::Status, "ok");
    LT_IM(OTA, "aborted");
}

template <typename T>
void CommandHandlerT<T>::_cmdOtaChunk(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    if (!_ota->isRunning()) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "No OTA in progress");
        return;
    }
    String b64;
    if (!payload.getString(protocol::FieldId::Data, b64) || b64.length() == 0) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Missing data");
        return;
    }
    size_t b64Len = b64.length();
    size_t decodedMax = (b64Len * 3) / 4 + 4;
    uint8_t* buf = new uint8_t[decodedMax];
    size_t olen = 0;
    if (!crypto::base64Decode(b64.c_str(), b64Len, buf, decodedMax, &olen)) {
        delete[] buf;
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Base64 decode failed");
        return;
    }
    bool ok = _ota->writeChunk(buf, olen);
    delete[] buf;
    if (!ok) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Write chunk failed");
    } else {
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setU32(protocol::FieldId::Received, (uint32_t)olen);
    }
}

template <typename T>
void CommandHandlerT<T>::_cmdGetSystemInfo(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    resp.setString(protocol::FieldId::Status, "ok");

    bool all = true;
    String fields;
    if (payload.getString(protocol::FieldId::Fields, fields)) {
        all = (fields == "all");
    }

    auto has = [&](const char* name) -> bool {
        if (all) return true;
        return payload.arrayContainsString(protocol::FieldId::Fields, name);
    };

    if (has("system")) {
        auto sys = resp.beginObject(protocol::FieldId::System);
        if (sys) {
            sys->setU32(protocol::FieldId::ChipId, chip::systemChipId());
            sys->setString(protocol::FieldId::ChipModel, chip::chipModelName());
            sys->setU32(protocol::FieldId::CpuFreq, ESP.getCpuFreqMHz());
            sys->setString(protocol::FieldId::SdkVersion, ESP.getSdkVersion());
            sys->setString(protocol::FieldId::BuildTime, buildStr());
            sys->setU32(protocol::FieldId::BuildUnixTime, buildUnixTime());
            sys->setU32(protocol::FieldId::Uptime, millis() / 1000);

            time_t raw = _log ? _log->getEpoch() : 0;
            struct tm ti;
            gmtime_r(&raw, &ti);
            char buf[26];
            snprintf_P(buf, sizeof(buf), PSTR("%02d-%02d-%04d %02d:%02d:%02d"), ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900, ti.tm_hour, ti.tm_min, ti.tm_sec);
            sys->setString(protocol::FieldId::TimeSys, buf);
            sys->setString(protocol::FieldId::ResetReason, chip::systemResetReason());
            resp.endObject(sys);
        }
    }

    if (has("memory")) {
        auto mem = resp.beginObject(protocol::FieldId::Memory);
        if (mem) {
            mem->setU32(protocol::FieldId::FreeHeap, ESP.getFreeHeap());
            mem->setU32(protocol::FieldId::MinEverFreeHeap, (uint32_t)chip::heapMinFree());
            mem->setU32(protocol::FieldId::MaxAllocHeap, (uint32_t)chip::heapMaxAlloc());
            resp.endObject(mem);
        }
    }

    if (has("tasks")) {
        UBaseType_t numTasks = uxTaskGetNumberOfTasks();
        TaskStatus_t* taskArray = (TaskStatus_t*)pvPortMalloc(numTasks * sizeof(TaskStatus_t));
        if (taskArray) {
            auto tasks = resp.beginArray(protocol::FieldId::Tasks);
            UBaseType_t count = uxTaskGetSystemState(taskArray, numTasks, nullptr);
            for (UBaseType_t i = 0; i < count; i++) {
                auto t = tasks->addBeginObject();
                t->setString(protocol::FieldId::Name, taskArray[i].pcTaskName);
                t->setU32(protocol::FieldId::Priority, taskArray[i].uxCurrentPriority);
                t->setU32(protocol::FieldId::StackWaterMark, taskArray[i].usStackHighWaterMark);
                switch (taskArray[i].eCurrentState) {
                case eRunning:   t->setString(protocol::FieldId::State, "running"); break;
                case eReady:     t->setString(protocol::FieldId::State, "ready"); break;
                case eBlocked:   t->setString(protocol::FieldId::State, "blocked"); break;
                case eSuspended: t->setString(protocol::FieldId::State, "suspended"); break;
                case eDeleted:   t->setString(protocol::FieldId::State, "deleted"); break;
                default:         t->setString(protocol::FieldId::State, "other"); break;
                }
                tasks->endObject(t);
            }
            vPortFree(taskArray);
            resp.endArray(tasks);
        }
    }

    if (has("wifi")) {
        auto w = resp.beginObject(protocol::FieldId::Wifi);
        if (w) {
            w->setI32(protocol::FieldId::Rssi, WiFi.RSSI());
            w->setString(protocol::FieldId::Ssid, (WiFi.getMode() == WIFI_AP) ? WiFi.softAPSSID() : WiFi.SSID());
            IPAddress ip = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPIP() : WiFi.localIP();
            char ipBuf[16];
            snprintf_P(ipBuf, sizeof(ipBuf), PSTR("%d.%d.%d.%d"), ip[0], ip[1], ip[2], ip[3]);
            w->setString(protocol::FieldId::Ip, ipBuf);
            w->setString(protocol::FieldId::Mac, WiFi.macAddress());
            w->setI32(protocol::FieldId::Channel, WiFi.channel());
            switch (_cfg->get().connMode) {
            case ConnMode::AP_WS:     w->setString(protocol::FieldId::ConnMode, "ap_ws"); break;
            case ConnMode::STA_MQTT:  w->setString(protocol::FieldId::ConnMode, "sta_mqtt"); break;
            case ConnMode::DEBUG_WS:  w->setString(protocol::FieldId::ConnMode, "debug_ws"); break;
            }
            w->setFloat(protocol::FieldId::Temperature, chip::readWifiTempC());
            resp.endObject(w);
        }
    }

    if (has("storage")) {
        auto s = resp.beginObject(protocol::FieldId::Storage);
        if (s) {
            s->setU32(protocol::FieldId::FlashSize, ESP.getFlashChipSize());
            s->setU32(protocol::FieldId::FsTotal, (uint32_t)compat::fsTotalBytes());
            const char* modeStr = "UNKNOWN";
            switch (chip::getFlashChipMode()) {
                case 0: modeStr = "QIO"; break;
                case 1: modeStr = "QOUT"; break;
                case 2: modeStr = "DIO"; break;
                case 3: modeStr = "DOUT"; break;
            }
            s->setString(protocol::FieldId::FlashMode, modeStr);
            s->setU32(protocol::FieldId::FlashSpeed, chip::getFlashChipSpeed());
            resp.endObject(s);
        }
    }

    if (has("pump")) {
        if (_driver) {
            _driver->getSysInfo(resp);
        }
    }

    bool stream = false;
    if (payload.getBool(protocol::FieldId::Stream, stream) && stream) {
        startStream(STREAM_SYSINFO, source, STREAM_DURATION_MS);
    }
}

template <typename T>
void CommandHandlerT<T>::_onScanDone() {
    _scanPending = false;
    _scanResultLen = 0;

    int16_t count = compat::scanComplete();
    if (count >= 0) {
        chip::ScanResult results[40];
        int n = chip::scanGetResults(results, 40);
        
        protocol::BinaryWriter writer(protocol::CommandId::GetScanWifiData);
        writer.writeString(protocol::FieldId::Status, "ok", 2);
        
        auto nets = writer.beginArray(protocol::FieldId::Networks);
        for (int i = 0; i < n && i < count; i++) {
            auto item = writer.beginObject(protocol::FieldId::None);
            writer.writeString(protocol::FieldId::Name, results[i].ssid);
            writer.writeI32(protocol::FieldId::Rssi, results[i].rssi);
            char bssid[18];
            snprintf_P(bssid, sizeof(bssid), PSTR("%02X:%02X:%02X:%02X:%02X:%02X"),
                results[i].bssid[0], results[i].bssid[1], results[i].bssid[2],
                results[i].bssid[3], results[i].bssid[4], results[i].bssid[5]);
            writer.writeString(protocol::FieldId::Bssid, bssid);
            writer.writeBool(protocol::FieldId::IsEncrypt, results[i].isEncrypt);
            item.end();
        }
        nets.end();

        if (_scanResultBuf) free(_scanResultBuf);
        _scanResultBuf = (uint8_t*)malloc(writer.size());
        if (_scanResultBuf) {
            memcpy(_scanResultBuf, writer.data(), writer.size());
            _scanResultLen = writer.size();
        }
    } else {
        protocol::BinaryWriter writer(protocol::CommandId::GetScanWifiData);
        writer.writeString(protocol::FieldId::Status, "error", 5);
        writer.writeString(protocol::FieldId::Message, "Scan failed", 11);

        if (_scanResultBuf) free(_scanResultBuf);
        _scanResultBuf = (uint8_t*)malloc(writer.size());
        if (_scanResultBuf) {
            memcpy(_scanResultBuf, writer.data(), writer.size());
            _scanResultLen = writer.size();
        }
    }

    _scanResultReady = true;
    compat::scanDelete();

    // Async notify scan completion
    protocol::BinaryWriter notifyWriter(protocol::CommandId::ScanWifi);
    notifyWriter.writeString(protocol::FieldId::Status, "completed", 9);
    _sendBinaryResponse(_scanSource, notifyWriter.data(), notifyWriter.size());
}

template <typename T>
void CommandHandlerT<T>::_cmdScanWifi(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)payload;

    if (_scanPending) {
        if (millis() - _scanStartMs >= kScanTimeoutMs) {
            LT_IM(CMD, "Scan watchdog: previous scan timed out, allowing restart");
            _scanPending = false;
        } else {
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Scan already in progress");
            return;
        }
    }

    _scanPending = true;
    _scanStartMs = millis();
    _scanSource = source;

    LT_IM(CMD, "Starting async WiFi scan...");

    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Message, "Scan started");
    resp.setBool(protocol::FieldId::WifiDrop, false);

    compat::scanAsync([this]() {
        this->_onScanDone();
    });
}

template <typename T>
void CommandHandlerT<T>::_cmdGetScanWifiData(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    (void)payload;

    if (_scanPending) {
        if (millis() - _scanStartMs >= kScanTimeoutMs) {
            LT_IM(CMD, "Scan watchdog: timeout while waiting for scan results");
            _scanPending = false;
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Scan failed");
            return;
        }
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Scan still in progress");
        return;
    }

    resp.setString(protocol::FieldId::Status, "error");
    resp.setString(protocol::FieldId::Message, "No scan data available");
}

template <typename T>
void CommandHandlerT<T>::_cmdPair(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    if (_cfg->get().connMode != ConnMode::AP_WS) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Pairing only allowed in AP mode");
        return;
    }

    String ssid;
    if (!payload.getString(protocol::FieldId::WifiSSID, ssid) || ssid.length() == 0) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Missing or invalid 'wifiSsid'");
        return;
    }

    if (!_identity) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Identity unavailable");
        return;
    }

    String ck;
    payload.getString(protocol::FieldId::ControlKey, ck);
    if (ck.length() == 0 || !_identity->setControlKeyHex(ck.c_str())) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Missing or invalid 'controlKey' (need 64 hex chars)");
        return;
    }
    _resetSeq();

    T& c = _cfg->get();
    strlcpy(c.wifiSSID, ssid.c_str(), sizeof(c.wifiSSID));
    String pass;
    payload.getString(protocol::FieldId::WifiPass, pass);
    strlcpy(c.wifiPass, pass.c_str(), sizeof(c.wifiPass));

    String s;
    if (payload.getString(protocol::FieldId::MqttServer, s) && s.length() > 0) {
        strlcpy(c.mqttServer, s.c_str(), sizeof(c.mqttServer));
    }
    uint32_t u = 0;
    if (payload.getUint(protocol::FieldId::MqttPort, u)) {
        c.mqttPort = (uint16_t)u;
    }
    if (payload.getString(protocol::FieldId::MqttUser, s) && s.length() > 0) {
        strlcpy(c.mqttUser, s.c_str(), sizeof(c.mqttUser));
    }
    if (payload.getString(protocol::FieldId::MqttPass, s) && s.length() > 0) {
        _cfg->setPassPlain(s.c_str());
    }

    c.connMode = ConnMode::STA_MQTT;
    _cfg->save(c);

    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Message, "Pairing saved. Rebooting...");
    if (_identity) {
        resp.setString(protocol::FieldId::DeviceId, _identity->deviceId());
        resp.setString(protocol::FieldId::PairingState, _identity->isProvisioned() ? "provisioned" : "unprovisioned");
    }

    LT_IM(CMD, "Pairing: ssid=%s connMode=STA_MQTT, rebooting", ssid.c_str());
    delay(1200);
    ESP.restart();
}

template <typename T>
void CommandHandlerT<T>::_cmdProvision(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    if (_cfg->get().connMode != ConnMode::AP_WS) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Provision only allowed in AP mode");
        return;
    }
    if (!_identity) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Identity unavailable");
        return;
    }

    String ck;
    if (payload.getString(protocol::FieldId::ControlKey, ck) && ck.length() > 0) {
        if (!_identity->setControlKeyHex(ck.c_str())) {
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Invalid controlKey (need 64 hex chars)");
            return;
        }
        _resetSeq();
    }

    resp.setString(protocol::FieldId::Status, "ok");
    if (_identity) {
        resp.setString(protocol::FieldId::DeviceId, _identity->deviceId());
        resp.setString(protocol::FieldId::PairingState, _identity->isProvisioned() ? "provisioned" : "unprovisioned");
        String curKey;
        if (_identity->controlKeyHex(curKey)) resp.setString(protocol::FieldId::ControlKey, curKey);
    }
    LT_IM(CMD, "Provision: deviceId=%s state=%s", _identity->deviceId(), _identity->isProvisioned() ? "provisioned" : "unprovisioned");
}

template <typename T>
void CommandHandlerT<T>::_handleFileCommand(const String& source, protocol::CommandId cmdId, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)source;
    String path;
    if (!payload.getString(protocol::FieldId::Path, path) || path.length() == 0) {
        path = "/";
    }

    uint32_t offset = 0;
    payload.getUint(protocol::FieldId::Offset, offset);

    uint32_t limit = 0;
    payload.getUint(protocol::FieldId::Limit, limit);

    bool encode = false;
    payload.getBool(protocol::FieldId::Encode, encode);

    if (cmdId == protocol::CommandId::ListDir) {
        FileBrowser::listDir(path, offset, limit, resp);
    } else if (cmdId == protocol::CommandId::FileInfo) {
        FileBrowser::fileInfo(path, resp);
    } else if (cmdId == protocol::CommandId::DeleteItem) {
        FileBrowser::deleteItem(path, resp);
    } else if (cmdId == protocol::CommandId::FsInfo) {
        FileBrowser::fsInfo(resp);
    } else if (cmdId == protocol::CommandId::DownloadFile || cmdId == protocol::CommandId::ReadFile) {
        if (limit == 0) limit = (cmdId == protocol::CommandId::DownloadFile) ? 1024 : 4096;
        if (cmdId == protocol::CommandId::DownloadFile) encode = true;
        FileBrowser::readFile(path, offset, limit, encode, resp);
    }
}
