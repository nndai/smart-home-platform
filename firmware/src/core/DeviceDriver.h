#pragma once

#include <ArduinoJson.h>
#include <functional>

#include "core/ConfigManager.h"
#include "core/log/LogManager.h"

// ── Dịch vụ core cấp cho driver: log, lưu/reset config, gửi phản hồi ──
// LED/nút nhấn KHÔNG nằm ở đây: chúng là phần cứng của thiết bị, driver tự khai
// báo + tự quản (vd pump 1 LED 1 button, fan 5 LED 2 button, thêm bớt tùy profile).
struct DriverServices {
    LogManager* log = nullptr;
    std::function<bool()> saveConfig;
    std::function<void()> resetConfig;
    std::function<void(const String&)> sendResponse;
    std::function<bool()> isConnected;
};

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

    // UI hooks: core cấp services; driver tự gắn callbacks button/LED (hành vi riêng từng device).
    virtual void setServices(const DriverServices& svc) { (void)svc; }
    virtual bool isRelayOn() { return false; }
    virtual void setRelay(bool on) { (void)on; }

    // Dành cho thiết bị có target (vd: Remote Switch) để nhận status từ target
    virtual void handleTargetStatus(const JsonDocument& doc) { (void)doc; }
};
