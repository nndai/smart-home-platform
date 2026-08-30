#pragma once

#include <Arduino.h>
#include <functional>
#include <Config.h>
#include "compat/task.h"
#include "compat/fs.h"
#include "compat/kv.h"

#include "core/ConfigManager.h"
#include "core/DeviceDriver.h"
#include "core/DeviceIdentity.h"
#include "core/MqttClient.h"
#include "core/WebSocketServer.h"
#include "core/log/LogManager.h"
#include "core/OTAManager.h"
#include "core/FileBrowser.h"
#include "protocol/CommandContext.h"
#include "protocol/CommandContextBinary.h"
#include "protocol/BinaryProtocol.h"
#include "protocol/BinaryCommandIds.h"

// CommandHandler xử lý mọi command dùng chung; command riêng của thiết bị
// (setRelay, calibrate...) được chuyển cho DeviceDriver (xem setDriver).
// Template theo kiểu config của profile (PumpConfig, SwitchConfig...).
template <typename T>
class CommandHandlerT {
public:
    using BinaryResponseCallback = std::function<void(const String& target, const uint8_t* payload, size_t length)>;

    enum StreamType : uint8_t {
        STREAM_STATUS   = 0,
        STREAM_SYSINFO  = 1,
        STREAM_COUNT    = 2
    };

    CommandHandlerT();
    void begin(ConfigManagerT<T>* cfg, LogManager* log, OTAManager* ota,
               DeviceIdentity* identity, const char* profile);
    void setDriver(DeviceDriver* driver) { _driver = driver; }
    void setBinaryResponseCallback(BinaryResponseCallback cb);
    void handleCommandBinary(const String& source, const uint8_t* payload, size_t length);

    void startStream(StreamType type, const String& source, unsigned long durationMs);
    bool isStreamActive(StreamType type) const;
    void sendStream(StreamType type);
    bool anyStreamActive() const;

    // Publish status snapshot của chính thiết bị lên devices/{id}/up.
    void publishStatusToUp();

private:
    ConfigManagerT<T>* _cfg;
    DeviceDriver* _driver = nullptr;
    LogManager* _log;
    OTAManager* _ota;
    DeviceIdentity* _identity = nullptr;
    String _profile;
    BinaryResponseCallback _binaryResponseCb;

    // ── Streams: deadline & source riêng cho mỗi loại ──
    unsigned long _streamDeadline[STREAM_COUNT] = {0, 0};
    String _streamSource[STREAM_COUNT] = {"", ""};

    // ── WiFi scan state ──
    bool _scanPending = false;
    unsigned long _scanStartMs = 0;
    String _scanSource;
    uint8_t* _scanResultBuf = nullptr;
    size_t _scanResultLen = 0;
    bool _scanResultReady = false;

    void _sendBinaryResponse(const String& source, const uint8_t* data, size_t length);

    // ── Envelope lệnh qua MQTT (docs §3.2): chống giả mạo + replay ──
    bool _verifyEnvelope(protocol::CommandId cmdId, const protocol::CommandRequest& req);

    // Replay protection via timestamp drift window
    static constexpr uint32_t ENVELOPE_TS_WINDOW_S = 300;   // |now - ts| <= 300s (5 min window)
    static constexpr unsigned long kScanTimeoutMs = 20000;

    void _cmdGetStatus(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdGetConfig(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdSetConfig(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdGetLog(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdClearSysLog(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdOtaUrl(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdReboot(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdFactoryReset(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdScanWifi(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _onScanDone();
    void _cmdGetScanWifiData(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdGetLogStats(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdUploadFirmwareStart(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdUploadFirmwareEnd(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdUploadFirmwareAbort(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdGetSystemInfo(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdOtaChunk(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdSetLogMqtt(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdGetLogMqtt(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdPair(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _cmdProvision(const String& source, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
    void _handleFileCommand(const String& source, protocol::CommandId cmdId, const protocol::CommandRequest& payload, protocol::CommandResponse& resp);
};

// ── Định nghĩa template (để main.cpp explicit-instantiate được) ──
#include "core/CommandHandler.ipp"
