#pragma once

#include "core/DeviceDriver.h"
#include "core/devices/RelayController.h"

// ── Driver của profile SWITCH (relay đơn, không triac/BL0937/NTC) ──
class SwitchDriver : public DeviceDriver {
public:
    void begin(DeviceConfig& cfg, ConfigSaveFn saveFn) override;
    void loop(uint32_t nowMs) override;
    bool handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) override;
    void getStatus(JsonDocument& resp) override;
    void getConfig(JsonDocument& resp) override;
    bool setConfig(const JsonDocument& payload, JsonDocument& resp) override;

    bool isRelayOn() override { return _relay.getState(); }
    void setRelay(bool on) override { on ? _relay.turnOn() : _relay.turnOff(); }
    void setLog(LogManager* log) { _log = log; }

private:
    RelayController _relay;

    DeviceConfig* _cfg = nullptr;
    LogManager* _log = nullptr;

    void _persistRelayState();
};
