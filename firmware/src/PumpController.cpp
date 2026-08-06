#include "PumpController.h"
#include "RelayController.h"
#include <Config.h>

PumpController::PumpController()
    : _relay(nullptr)
    , _state(PumpState::OFF)
    , _threshOff(DEFAULT_THRESH_OFF)
    , _threshNoWater(DEFAULT_THRESH_NO_WATER)
    , _threshRunning(DEFAULT_THRESH_RUNNING)
    , _threshOverload(DEFAULT_THRESH_OVERLOAD)
    , _dryTimeout(DEFAULT_NO_WATER_TIMEOUT)
    , _overloadTimeout(DEFAULT_OVERLOAD_TIMEOUT)
    , _dryStart(0)
    , _criticalStart(0)
    , _overloadStart(0)
    , _pumpMode(true)
    , _faultLatched(false)
{
}

void PumpController::begin(RelayController* relay, bool pumpMode) {
    _relay = relay;
    _pumpMode = pumpMode;
    _state = PumpState::OFF;
    _faultLatched = false;
}

void PumpController::setThresholds(uint16_t off, uint16_t noWater, uint16_t running, uint16_t overload) {
    _threshOff = off;
    _threshNoWater = noWater;
    _threshRunning = running;
    _threshOverload = overload;
}

void PumpController::setTimeouts(uint16_t dryTimeout, uint16_t overloadTimeout) {
    _dryTimeout = dryTimeout;
    _overloadTimeout = overloadTimeout;
}

void PumpController::update(float currentAmps) {
    if (_relay) _relay->handle();

    unsigned long now = millis();
    float currentMa = currentAmps * 1000.0f;

    if (_faultLatched) return;

    PumpState newState = _state;

    if (!_pumpMode) {
        // Switch mode: OFF / RUNNING_OK / OVERLOAD
        if (currentMa < _threshOff) {
            newState = PumpState::OFF;
            _overloadStart = 0;
        } else if (currentMa >= _threshOverload) {
            if (_overloadStart == 0) {
                _overloadStart = now;
            } else if (now - _overloadStart >= _overloadTimeout) {
                newState = PumpState::OVERLOAD;
            }
        } else {
            newState = PumpState::RUNNING_OK;
            _overloadStart = 0;
        }
    } else {
        // Pump mode: OFF / HIGH_CURRENT / DRY_RUN / CRITICAL_CURRENT / OVERLOAD
        if (currentMa < _threshOff) {
            newState = PumpState::OFF;
            _dryStart = 0;
            _criticalStart = 0;
            _overloadStart = 0;
        } else if (currentMa >= _threshOverload) {
            if (_overloadStart == 0) {
                _overloadStart = now;
            } else if (now - _overloadStart >= _overloadTimeout) {
                newState = PumpState::OVERLOAD;
            }
            _dryStart = 0;
            _criticalStart = 0;
        } else if (currentMa >= _threshRunning * PUMP_CRITICAL_PERCENT / 100) {
            _dryStart = 0;
            _overloadStart = 0;
            if (_criticalStart == 0) {
                _criticalStart = now;
            } else if (now - _criticalStart >= _dryTimeout) {
                newState = PumpState::CRITICAL_CURRENT;
            }
        } else if (currentMa >= _threshRunning) {
            newState = PumpState::HIGH_CURRENT;
            _dryStart = 0;
            _criticalStart = 0;
            _overloadStart = 0;
        } else if (currentMa < _threshNoWater) {
            _overloadStart = 0;
            _criticalStart = 0;
            if (_dryStart == 0) {
                _dryStart = now;
            } else if (now - _dryStart >= _dryTimeout) {
                newState = PumpState::DRY_RUN;
            }
        } else {
            newState = PumpState::RUNNING_OK;
            _dryStart = 0;
            _criticalStart = 0;
            _overloadStart = 0;
        }
    }

    if (newState != _state) {
        _state = newState;
        _faultLatched = (newState == PumpState::OVERLOAD ||
                         newState == PumpState::DRY_RUN ||
                         newState == PumpState::CRITICAL_CURRENT);
        if (_faultLatched) {
            if (_relay) _relay->turnOff();
        }
        const char* msg = "";
        switch (newState) {
            case PumpState::OFF:              msg = "Pump OFF"; break;
            case PumpState::RUNNING_OK:       msg = "Pump OK - water flowing"; break;
            case PumpState::HIGH_CURRENT:     msg = "HIGH CURRENT - current too high"; break;
            case PumpState::DRY_RUN:          msg = "DRY RUN - no water!"; break;
            case PumpState::CRITICAL_CURRENT: msg = "CRITICAL CURRENT - turning off"; break;
            case PumpState::OVERLOAD:         msg = "OVERLOAD - current too high!"; break;
        }
        if (_eventCb) {
            _eventCb(_state, currentAmps, isOn(), msg);
        }
    }
}

void PumpController::clearPumpFault() {
    _faultLatched = false;
    _dryStart = 0;
    _criticalStart = 0;
    _overloadStart = 0;
    if (_state == PumpState::DRY_RUN || _state == PumpState::CRITICAL_CURRENT || _state == PumpState::OVERLOAD) {
        _state = PumpState::OFF;
        if (_eventCb) {
            _eventCb(_state, 0.0f, isOn(), "Pump OFF");
        }
    }
}

void PumpController::turnOn() {
    _faultLatched = false;
    if (_relay) _relay->turnOn();
    if (_eventCb) {
        _eventCb(_state, 0.0f, isOn(), "Pump ON");
    }
}

void PumpController::turnOff() {
    _faultLatched = false;
    if (_relay) _relay->turnOff();
    if (_eventCb) {
        _eventCb(_state, 0.0f, isOn(), "Pump OFF");
    }
}

void PumpController::toggle() {
    if (isOn()) {
        turnOff();
    } else {
        turnOn();
    }
}

void PumpController::setEventCallback(EventCallback cb) {
    _eventCb = cb;
}

void PumpController::setPumpMode(bool pumpMode) {
    _pumpMode = pumpMode;
}

void PumpController::reset() {
    _state = PumpState::OFF;
    _faultLatched = false;
    _dryStart = 0;
    _criticalStart = 0;
    _overloadStart = 0;
}
