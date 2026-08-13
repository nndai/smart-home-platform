#pragma once

#include "core/DeviceDriver.h"
#include "core/ButtonMenu.h"
#include "core/LedController.h"
#include <OneButton.h>
#include "core/log/LogManager.h"
#include "profiles/pump/CurrentSensor.h"
#include "profiles/pump/TemperatureSensor.h"
#include "profiles/pump/PumpController.h"
#include "profiles/pump/PumpConfig.h"
#include "core/devices/MainsSwitch.h"

// ── Driver của profile PUMP (bơm + BL0937 + NTC + relay + triac) ──
class PumpDriver : public DeviceDriver {
public:
    void begin(DeviceConfig& cfg, ConfigSaveFn saveFn) override;
    void loop(uint32_t nowMs) override;
    bool handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) override;
    void getStatus(JsonDocument& resp) override;
    void getConfig(JsonDocument& resp) override;
    bool setConfig(const JsonDocument& payload, JsonDocument& resp) override;
    void getSysInfo(JsonDocument& resp) override;

    void setServices(const DriverServices& svc) override;
    bool isRelayOn() override { return _pump.isOn(); }
    void setRelay(bool on) override { on ? _pump.turnOn() : _pump.turnOff(); }

private:
    CurrentSensor _current;
    TemperatureSensor _temp;
    MainsSwitch _switch;
    PumpController _pump;
    ButtonMenu _menu;

    // ── UI riêng của pump: 1 LED trạng thái + 1 nút nhấn ──
    // Thêm LED/button nữa (vd PIN_LED2) = thêm 1 member + 2 dòng (begin/update).
    LedController _led;
    OneButton _button;

    PumpConfig* _cfg = nullptr;
    ConfigSaveFn _saveFn;
    LogManager* _log = nullptr;
    std::function<bool()> _saveConfig;
    std::function<void()> _resetConfig;
    std::function<void(const String&)> _sendResponse;
    ButtonMenu::Step _menuSteps[3];

    // ── Nhịp nội bộ ──
    unsigned long _lastSensorLoop = 0;
    uint32_t _lastHourEpoch = UINT32_MAX;
    uint32_t _hourRefMillis = 0;
    uint32_t _lastDayEpoch = UINT32_MAX;
    uint32_t _lastEnergyCheck = 0;
    uint32_t _lastPumpTimeAdd = 0;

    void _onPumpState(PumpState state, float current, bool isOn, const char* msg);
    void _energyTick();
    void _handleCalibrate(const JsonDocument& payload, JsonDocument& resp);

    // ── Hành vi nút nhấn (riêng của pump) ──
    void _onButtonClick();
    void _onButtonDoubleClick();
    void _onButtonLongPressStart();
    void _menuResetWiFi();
    void _menuDebugMode();
    void _menuFactoryReset();
};
