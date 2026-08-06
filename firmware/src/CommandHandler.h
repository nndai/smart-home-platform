#pragma once

#include <Arduino.h>
#include <ArduinoJson.h>
#include <functional>
#include <Config.h>
#include <FreeRTOS.h>
#include <task.h>
#include <semphr.h>
#include <LittleFS.h>

#include "ConfigManager.h"
#include "CurrentSensor.h"
#include "TemperatureSensor.h"
#include "PumpController.h"
#include "MqttClient.h"
#include "WebSocketServer.h"
#include "log/LogManager.h"
#include "OTAManager.h"
#include "FileBrowser.h"

class CommandHandler {
public:
    using ResponseCallback = std::function<void(const String& target, const String& json)>;

    enum StreamType : uint8_t {
        STREAM_STATUS   = 0,
        STREAM_SYSINFO  = 1,
        STREAM_COUNT    = 2
    };

    CommandHandler();
    void begin(ConfigManager* cfg, CurrentSensor* current, TemperatureSensor* temp,
               PumpController* pump, LogManager* log,
               OTAManager* ota);
    void setResponseCallback(ResponseCallback cb);
    void handleCommand(const String& source, const String& json);

    void startStream(StreamType type, const String& source, unsigned long durationMs);
    bool isStreamActive(StreamType type) const;
    void sendStream(StreamType type);
    bool anyStreamActive() const;

private:
    ConfigManager* _cfg;
    CurrentSensor* _current;
    TemperatureSensor* _temp;
    PumpController* _pump;
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

    void _cmdSetRelay(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetStatus(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdSetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLog(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdClearSysLog(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdOtaUrl(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdReboot(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdFactoryReset(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdCalibrate(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdResetCalibration(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdClearPumpFault(const String& source, const JsonDocument& payload, JsonDocument& resp);
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
