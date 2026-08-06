#pragma once

#include <Arduino.h>
#include <functional>

#include "RelayController.h"

enum class PumpState {
    OFF,
    RUNNING_OK,
    HIGH_CURRENT,      // > _threshRunning (pump mode only, transient)
    DRY_RUN,           // pump mode, latched
    CRITICAL_CURRENT,  // > _threshRunning*125%, pump mode, latched
    OVERLOAD           // all modes, latched
};

class PumpController {
public:
    using EventCallback = std::function<void(PumpState state, float current, bool isOn, const char* message)>;

    PumpController();
    void begin(RelayController* relay, bool pumpMode);
    void setThresholds(uint16_t off, uint16_t noWater, uint16_t running, uint16_t overload);
    void setTimeouts(uint16_t dryTimeout, uint16_t overloadTimeout);
    void update(float currentAmps);
    void setPumpMode(bool pumpMode);
    PumpState getState() const { return _state; }
    void turnOn();
    void turnOff();
    void toggle();
    bool isOn() const { return _relay ? _relay->getState() : false; }
    unsigned long getOnDuration() const { return _relay ? _relay->getOnDuration() : 0; }
    void clearPumpFault();
    bool isFaultLatched() const { return _faultLatched; }
    void setEventCallback(EventCallback cb);
    void setOnDurationCallback(std::function<void(unsigned long)> cb) { if (_relay) _relay->setOnDurationCallback(cb); }
    void reset();

private:
    RelayController* _relay;
    PumpState _state;
    uint16_t _threshOff;
    uint16_t _threshNoWater;
    uint16_t _threshRunning;
    uint16_t _threshOverload;
    uint16_t _dryTimeout;
    uint16_t _overloadTimeout;
    unsigned long _dryStart;
    unsigned long _criticalStart;
    unsigned long _overloadStart;
    EventCallback _eventCb;
    bool _pumpMode;
    bool _faultLatched;
};
