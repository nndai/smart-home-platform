#include "RelayController.h"

RelayController::RelayController()
    : _relayPin(-1)
    , _triacGatePin(-1)
    , _on(false)
    , _triacOn(false)
    , _onTime(0)
    , _lastAction(0)
    , _step(0)
{
}

void RelayController::begin(int relayPin, int triacGatePin) {
    _relayPin = relayPin;
    _triacGatePin = triacGatePin;

    pinMode(_relayPin, OUTPUT);
    pinMode(_triacGatePin, OUTPUT);

    digitalWrite(_relayPin, LOW);
    digitalWrite(_triacGatePin, LOW);
}

void RelayController::_triacFire() {
    digitalWrite(_triacGatePin, HIGH);
    _triacOn = true;
}

void RelayController::_triacOff() {
    digitalWrite(_triacGatePin, LOW);
    _triacOn = false;
}

// ── ON ──
// step 1: TRIAC fires → wait 20ms → relay closes
// step 2: relay closed → wait 20ms → TRIAC gate off (relay carries load)
void RelayController::turnOn() {
    if (_on) return;           // already fully on
    _on = true;
    _onTime = millis();

    if (_step == 3) {
        // Was turning OFF, relay still on, TRIAC already on
        // → skip firing, just wait then kill TRIAC
        _step = 2;
        _lastAction = millis();
        return;
    }
    if (_step == 4) {
        // Was turning OFF, relay already open, TRIAC still on
        // → close relay now, will kill TRIAC after settle
        digitalWrite(_relayPin, HIGH);
        _step = 2;
        _lastAction = millis();
        return;
    }

    // Fresh start
    _step = 1;
    _lastAction = millis();
    _triacFire();
}

// ── OFF ──
// step 3: TRIAC re-fires → wait 20ms → relay opens
// step 4: relay open → wait 20ms → TRIAC gate off (everything off)
void RelayController::turnOff() {
    if (!_on) {
        if (_step == 0) return;
        if (_step >= 3) return;
    }
    _on = false;

    if (_step == 1) {
        // Was turning ON, TRIAC on but relay never closed
        // → just kill TRIAC, cancel entirely
        _triacOff();
        _step = 0;
        return;
    }
    if (_step == 2) {
        // Was turning ON, relay closed but TRIAC still on
        // → TRIAC already conducting, skip re-fire, open relay
        _step = 3;
        _lastAction = millis();
        return;
    }

    // Fresh start (or abort→restart)
    _step = 3;
    _lastAction = millis();
    _triacFire();
}

bool RelayController::toggle() {
    if (_on) turnOff();
    else turnOn();
    return _on;
}

void RelayController::handle() {
    unsigned long now = millis();

    if (_step == 1) {
        // TRIAC on → close relay
        if (now - _lastAction >= T_TRIAC_SETTLE) {
            digitalWrite(_relayPin, HIGH);
            _lastAction = now;
            _step = 2;
        }
    } else if (_step == 2) {
        // Relay on → kill TRIAC gate
        if (now - _lastAction >= T_RELAY_SETTLE) {
            _triacOff();
            _step = 0;
        }
    } else if (_step == 3) {
        // TRIAC re-fired → open relay
        if (now - _lastAction >= T_TRIAC_SETTLE) {
            digitalWrite(_relayPin, LOW);
            if (_onDurationCb) {
                _onDurationCb(now - _onTime);
            }
            _lastAction = now;
            _step = 4;
        }
    } else if (_step == 4) {
        // Relay open → kill TRIAC gate
        if (now - _lastAction >= T_RELAY_SETTLE) {
            _triacOff();
            _step = 0;
        }
    }
}

unsigned long RelayController::getOnDuration() const {
    if (!_on) return 0;
    return millis() - _onTime;
}

void RelayController::setOnDurationCallback(std::function<void(unsigned long)> cb) {
    _onDurationCb = cb;
}
