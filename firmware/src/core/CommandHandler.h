#pragma once

#include <Arduino.h>
#include <ArduinoJson.h>
#include <functional>
#include <Config.h>
#include "compat/task.h"
#include <LittleFS.h>

#include "core/ConfigManager.h"
#include "core/DeviceDriver.h"
#include "core/MqttClient.h"
#include "core/WebSocketServer.h"
#include "core/log/LogManager.h"
#include "core/OTAManager.h"
#include "core/FileBrowser.h"

// CommandHandler xử lý mọi command dùng chung; command riêng của thiết bị
// (setRelay, calibrate...) được chuyển cho DeviceDriver (xem setDriver).
struct PumpConfig;

class CommandHandler {
public:
    using ResponseCallback = std::function<void(const String& target, const String& json)>;

    enum StreamType : uint8_t {
        STREAM_STATUS   = 0,
        STREAM_SYSINFO  = 1,
        STREAM_COUNT    = 2
    };

    CommandHandler();
    void begin(ConfigManagerT<PumpConfig>* cfg, LogManager* log, OTAManager* ota);
    void setDriver(DeviceDriver* driver) { _driver = driver; }
    void setResponseCallback(ResponseCallback cb);
    void handleCommand(const String& source, const String& json);

    void startStream(StreamType type, const String& source, unsigned long durationMs);
    bool isStreamActive(StreamType type) const;
    void sendStream(StreamType type);
    bool anyStreamActive() const;

private:
    ConfigManagerT<PumpConfig>* _cfg;
    DeviceDriver* _driver = nullptr;
    LogManager* _log;
    OTAManager* _ota;
    ResponseCallback _responseCb;

    // ── Streams: deadline & source riêng cho mỗi loại ──
    unsigned long _streamDeadline[STREAM_COUNT] = {0, 0};
    String _streamSource[STREAM_COUNT] = {"", ""};

    // ── WiFi scan state ──
    bool _scanPending = false;
    uint16_t _scanEventHandlerId = 0;
    String _scanSource;
    String _scanResultJson;
    bool _scanResultReady = false;

    void _sendResponse(const String& source, const JsonDocument& doc);
    void _sendResponse(const String& source, const String& json);
    void _handleCommand(const String& source, const JsonDocument& cmd, const JsonDocument& payload);

    void _cmdGetStatus(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdSetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLog(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdClearSysLog(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdOtaUrl(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdReboot(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdFactoryReset(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdScanWifi(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetScanWifiData(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLogStats(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdUploadFirmwareStart(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdUploadFirmwareEnd(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdUploadFirmwareAbort(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetSystemInfo(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdOtaChunk(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdSetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _handleFileCommand(const String& source, const String& cmd, const JsonDocument& payload, const String& reqId = "");
};
