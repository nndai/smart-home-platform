#pragma once

#include "core/DeviceDriver.h"
#include "RemoteSwitchConfig.h"
#include "core/ButtonMenu.h"
#include "core/LedController.h"
#include "core/log/LogManager.h"
#include <OneButton.h>

// ── Driver của profile REMOTE_SWITCH (nút điều khiển thiết bị khác qua MQTT) ──
// Không có relay riêng: nhận setRelay/toggle từ app, ký envelope bằng controlKey
// của target rồi publish tới devices/{targetId}/cmd; theo dõi trạng thái target
// qua devices/{targetId}/up (subscribe do core quản lý khi reconnect).
//
// LED: 2 cặp xanh/đỏ, mỗi đèn 1 chân riêng, tất cả active HIGH:
//   Cặp 1 (kết nối, updateConnectLeds) — check theo thứ tự wifi → mqtt → ntp → timeout:
//     wifi chưa connect : đỏ nháy 2 lần
//     mqtt chưa connect : đỏ nháy 3 lần
//     ntp chưa sync     : đỏ nháy 4 lần
//     ko nhận status    : đỏ sáng mãi (70s không có status từ target)
//     khỏe mạnh         : xanh sáng mãi
//   Cặp 2 (trạng thái target, updateStateLeds) — state machine TargetVisualState:
//     OFF     : tắt cả 2
//     ON_OK   : xanh sáng mãi (relay on + RUNNING OK)
//     WAITING : xanh nháy 500ms (chờ phản hồi setRelay, nút bị khóa)
//     ERROR   : đỏ nháy 500ms (timeout 5s / target lỗi; 60s không thao tác → OFF)
class RemoteSwitchDriver : public DeviceDriver {
public:
    RemoteSwitchDriver() : _cfg(nullptr) {}

    void begin(DeviceConfig& cfg, ConfigSaveFn saveFn) override;
    void loop(uint32_t nowMs) override;
    bool handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) override;
    void getStatus(JsonDocument& resp) override;
    void getConfig(JsonDocument& resp) override;
    bool setConfig(const JsonDocument& payload, JsonDocument& resp) override;
    void setServices(const DriverServices& svc) override;
    void handleTargetStatus(const JsonDocument& doc) override;

private:
    RemoteSwitchConfig* _cfg;
    DriverServices _services;
    ConfigSaveFn _saveCb;

    // ── LED: 2 cặp xanh/đỏ, 4 chân riêng, tất cả active HIGH ──
    LedController _ledConnRed;
    LedController _ledConnGreen;
    LedController _ledStateGreen;
    LedController _ledStateRed;
    OneButton _button;
    ButtonMenu _menu;
    ButtonMenu::Step _menuSteps[3];

    // ── Trạng thái visual (cặp 2) ──
    enum class TargetVisualState {
        OFF,       // tắt cả 2 đèn trạng thái
        ON_OK,     // xanh sáng mãi (relay on + RUNNING OK)
        WAITING,   // xanh nháy, chờ phản hồi setRelay (click bị khóa)
        ERROR      // đỏ nháy (timeout / target lỗi), 60s không thao tác → OFF
    };
    TargetVisualState _visualState = TargetVisualState::OFF;

    // ── Timing (millis, chống wrap bằng so sánh unsigned hiệu số) ──
    static constexpr uint32_t WAIT_RESPONSE_MS   = 5000;   // timeout chờ phản hồi setRelay
    static constexpr uint32_t ERROR_AUTOOFF_MS   = 60000;  // error không thao tác → tắt đèn
    static constexpr uint32_t STATUS_TIMEOUT_MS  = 70000;  // 1p10s không nhận status → lỗi connect
    static constexpr uint32_t CONNECT_BLINK_ON   = 300;    // cụm nháy đỏ (wifi/mqtt/ntp)
    static constexpr uint32_t CONNECT_BLINK_OFF  = 2000;
    static constexpr uint32_t CONNECT_AP_BLINK_MS = 500;   // xanh nháy đều khi ở AP (pairing)
    uint32_t _waitStartMs = 0;
    uint32_t _errorStartMs = 0;
    uint32_t _lastStatusRxMs = 0;

    // Status tracking (trạng thái target đã biết)
    bool _targetOn = false;
    bool _targetError = false;
    char _targetPumpStateStr[16] = ""; // "RUNNING OK" / "DRY RUN" / ... (chặt: chỉ xanh khi RUNNING OK)
    uint32_t _targetSeq = 0;

    void updateLeds(uint32_t nowMs);
    void updateConnectLeds(uint32_t nowMs);
    void updateStateLeds();
    void updateVisualFromStatus(uint32_t nowMs);
    bool isTargetRunningOk() const;
    void enterVisualState(TargetVisualState state, uint32_t nowMs);
    void sendRelayCommand(bool on);
    void sendToggleCommand() { sendRelayCommand(!_targetOn); }
    void requestStatusStream();
    bool buildEnvelope(const char* cmd, const JsonDocument& payload, JsonDocument& envelope);
    void subscribeTargetTopic();
    void updateTargetError(const JsonDocument& doc);

    // Callbacks for button
    void _onButtonClick();
    void _onButtonDoubleClick();
    void _onButtonLongPressStart();

    // Callbacks for menu
    void _menuResetWiFi();
    void _menuDebugMode();
    void _menuFactoryReset();
};
