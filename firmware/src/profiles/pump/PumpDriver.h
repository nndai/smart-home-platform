#pragma once

#include "core/DeviceDriver.h"
#include "core/log/LogManager.h"
#include "profiles/pump/CurrentSensor.h"
#include "profiles/pump/TemperatureSensor.h"
#include "profiles/pump/PumpController.h"
#include "profiles/pump/PumpConfig.h"
#include "core/devices/RelayController.h"

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

    void setLed(LedController* led) override { _led = led; }
    bool isRelayOn() override { return _pump.isOn(); }
    void setRelay(bool on) override { on ? _pump.turnOn() : _pump.turnOff(); }
    void setLog(LogManager* log) { _log = log; }

private:
    CurrentSensor _current;
    TemperatureSensor _temp;
    RelayController _relay;
    PumpController _pump;

    PumpConfig* _cfg = nullptr;
    ConfigSaveFn _saveFn;
    LedController* _led = nullptr;
    LogManager* _log = nullptr;

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
};
