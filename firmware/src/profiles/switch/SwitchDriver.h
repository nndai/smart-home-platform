#pragma once

#include "core/DeviceDriver.h"
#include "core/devices/MainsSwitch.h"
#include "profiles/switch/SwitchConfig.h"

// ── Driver của profile SWITCH (relay đơn, không triac/BL0937/NTC) ──
class SwitchDriver : public DeviceDriver {
public:
    void begin(DeviceConfig& cfg, ConfigSaveFn saveFn) override;
    void loop(uint32_t nowMs) override;
    bool handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) override;
    void getStatus(JsonDocument& resp) override;
    void getConfig(JsonDocument& resp) override;
    bool setConfig(const JsonDocument& payload, JsonDocument& resp) override;

    bool isRelayOn() override { return _switch.getState(); }
    void setRelay(bool on) override { on ? _switch.turnOn() : _switch.turnOff(); }
    void setServices(const DriverServices& svc) override { _log = svc.log; }

private:
    MainsSwitch _switch;

    SwitchConfig* _cfg = nullptr;
    LogManager* _log = nullptr;

    void _persistRelayState();
};
