#pragma once

#include <ArduinoJson.h>
#include <functional>

#include "core/ConfigManager.h"
#include "core/LedController.h"
#include "core/log/LogManager.h"

// ── Interface thiết bị: core gọi mù, profile hiện thực ──
// main.cpp + CommandHandler chỉ thao tác qua interface này,
// mọi thứ riêng của thiết bị nằm trong profiles/<device>/PumpDriver...
class DeviceDriver {
public:
    using ConfigSaveFn = std::function<bool()>;

    virtual ~DeviceDriver() {}

    // cfg = config của profile (DeviceConfig& của đối tượng thật); saveFn dùng để
    // driver tự lưu config khi cần (vd: ghi calibration default).
    virtual void begin(DeviceConfig& cfg, ConfigSaveFn saveFn) = 0;

    // Gọi trong vòng loop chính (mỗi ~20ms); driver tự giữ nhịp nội bộ.
    virtual void loop(uint32_t nowMs) = 0;

    // Command riêng của thiết bị (setRelay, calibrate...). Trả về true nếu đã xử lý.
    virtual bool handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) = 0;

    // Điền thêm field thiết bị vào resp (sau phần field chung của core).
    virtual void getStatus(JsonDocument& resp) = 0;
    virtual void getConfig(JsonDocument& resp) = 0;

    // Thêm object riêng thiết bị vào getSystemInfo (vd resp["pump"]). Default no-op.
    virtual void getSysInfo(JsonDocument& resp) { (void)resp; }

    // Đọc field thiết bị từ payload. Trả về true nếu có thay đổi.
    virtual bool setConfig(const JsonDocument& payload, JsonDocument& resp) = 0;

    // UI hooks (default no-op cho thiết bị không có)
    virtual void setLed(LedController* led) { (void)led; }
    virtual void setLog(LogManager* log) { (void)log; }
    virtual bool isRelayOn() { return false; }
    virtual void setRelay(bool on) { (void)on; }
};
