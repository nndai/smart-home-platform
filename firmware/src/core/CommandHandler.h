#pragma once

#include <Arduino.h>
#include <ArduinoJson.h>
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

// CommandHandler xử lý mọi command dùng chung; command riêng của thiết bị
// (setRelay, calibrate...) được chuyển cho DeviceDriver (xem setDriver).
// Template theo kiểu config của profile (PumpConfig, SwitchConfig...).
template <typename T>
class CommandHandlerT {
public:
    using ResponseCallback = std::function<void(const String& target, const String& json)>;

    enum StreamType : uint8_t {
        STREAM_STATUS   = 0,
        STREAM_SYSINFO  = 1,
        STREAM_COUNT    = 2
    };

    CommandHandlerT();
    void begin(ConfigManagerT<T>* cfg, LogManager* log, OTAManager* ota,
               DeviceIdentity* identity, const char* profile);
    void setDriver(DeviceDriver* driver) { _driver = driver; }
    void setResponseCallback(ResponseCallback cb);
    void handleCommand(const String& source, const String& json);

    void startStream(StreamType type, const String& source, unsigned long durationMs);
    bool isStreamActive(StreamType type) const;
    void sendStream(StreamType type);
    bool anyStreamActive() const;

    // Publish status snapshot của chính thiết bị lên devices/{id}/up.
    // Dùng cho việc tự báo định kỳ (stream rảnh: 60s, đang stream: 2s — xem
    // main.cpp taskStreamSender) và báo lỗi tức thì từ driver (pump DRY RUN...).
    // Đi thẳng vào _cmdGetStatus (không qua envelope: đây là status của mình).
    void publishStatusToUp();

private:
    ConfigManagerT<T>* _cfg;
    DeviceDriver* _driver = nullptr;
    LogManager* _log;
    OTAManager* _ota;
    DeviceIdentity* _identity = nullptr;
    String _profile;
    ResponseCallback _responseCb;

    // ── Streams: deadline & source riêng cho mỗi loại ──
    unsigned long _streamDeadline[STREAM_COUNT] = {0, 0};
    String _streamSource[STREAM_COUNT] = {"", ""};

    // ── WiFi scan state ──
    bool _scanPending = false;
    String _scanSource;
    JsonDocument _scanResultDoc;
    bool _scanResultReady = false;

    void _sendResponse(const String& source, const JsonDocument& doc);
    void _sendResponse(const String& source, const String& json);
    void _handleCommand(const String& source, const JsonDocument& cmd, const JsonDocument& payload);

    // ── Envelope lệnh qua MQTT (docs §3.2): chống giả mạo + replay ──
    // Mỗi sender (app, remote switch...) tự ký bằng cùng controlKey nhưng giữ
    // seq riêng → floor chống replay theo từng sender, không khóa nhau.
    bool _verifyEnvelope(const JsonDocument& cmd, const JsonDocument& payload);
    void _resetSeq();

    // Replay protection per sender (src = deviceId của sender; "" = legacy sender).
    // Bảng chỉ ở RAM, CỐ Ý không persist flash: sau reboot, replay bị chặn bởi
    // cửa sổ ts (|now-ts| <= 60s) — đánh đổi flash wear vs an toàn, đã chấp nhận.
    static constexpr uint32_t ENVELOPE_TS_WINDOW_S = 60;    // |now - ts| <= 60s
    static constexpr uint8_t MAX_SEQ_ENTRIES = 6;           // app + vài remote switch
    struct SeqEntry {
        char src[24];
        uint32_t seq;
    };
    SeqEntry _seqTable[MAX_SEQ_ENTRIES];
    uint8_t _seqCount = 0;

    void _cmdGetStatus(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdSetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLog(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdClearSysLog(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdOtaUrl(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdReboot(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdFactoryReset(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdScanWifi(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _onScanDone();
    void _cmdGetScanWifiData(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLogStats(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdUploadFirmwareStart(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdUploadFirmwareEnd(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdUploadFirmwareAbort(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetSystemInfo(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdOtaChunk(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdSetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdGetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdPair(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _cmdProvision(const String& source, const JsonDocument& payload, JsonDocument& resp);
    void _handleFileCommand(const String& source, const String& cmd, const JsonDocument& payload, const String& reqId = "");
};

// ── Định nghĩa template (để main.cpp explicit-instantiate được) ──
#include "core/CommandHandler.ipp"
