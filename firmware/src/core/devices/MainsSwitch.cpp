#include "core/devices/MainsSwitch.h"

MainsSwitch::MainsSwitch()
    : _relayPin(-1)
    , _triacPin(-1)
    , _relayActiveLow(true)
    , _triacActiveLow(true)
    , _mode(Mode::RELAY)
    , _on(false)
    , _triacOn(false)
    , _onTime(0)
    , _lastAction(0)
    , _step(0)
{
}

void MainsSwitch::begin(int relayPin, int triacGatePin,
                        bool relayActiveLow, bool triacActiveLow) {
    _relayPin = relayPin;
    _triacPin = triacGatePin;
    _relayActiveLow = relayActiveLow;
    _triacActiveLow = triacActiveLow;

    // Auto-select the mode from the elements that are actually wired.
    if (_relayPin >= 0 && _triacPin >= 0) {
        _mode = Mode::HYBRID;
    } else if (_triacPin >= 0) {
        _mode = Mode::TRIAC;
    } else {
        _mode = Mode::RELAY;
    }

    _on = false;
    _triacOn = false;
    _step = 0;

    if (_relayPin >= 0) {
        pinMode(_relayPin, OUTPUT);
        _driveRelay(false);  // de-energized at boot
    }
    if (_triacPin >= 0) {
        pinMode(_triacPin, OUTPUT);
        _driveTriac(false);  // gate off at boot
    }
}

void MainsSwitch::setMode(Mode mode) {
    switch (mode) {
        case Mode::HYBRID:
            if (_relayPin < 0 || _triacPin < 0) return;  // needs both elements
            break;
        case Mode::RELAY:
            if (_relayPin < 0) return;  // needs the relay
            break;
        case Mode::TRIAC:
            if (_triacPin < 0) return;  // needs the TRIAC
            break;
    }
    _interruptSequence();  // stop any pending HYBRID sequence cleanly
    _mode = mode;
}

// Drives the relay pin honoring its active level.
void MainsSwitch::_driveRelay(bool on) {
    if (_relayPin < 0) return;
    digitalWrite(_relayPin, on != _relayActiveLow ? HIGH : LOW);
}

// Drives the TRIAC gate pin honoring its active level.
void MainsSwitch::_driveTriac(bool on) {
    if (_triacPin < 0) return;
    digitalWrite(_triacPin, on != _triacActiveLow ? HIGH : LOW);
    _triacOn = on;
}

// Hard-stops any pending sequence: everything de-energized.
void MainsSwitch::_interruptSequence() {
    _driveTriac(false);
    _driveRelay(false);
    _triacOn = false;
    _on = false;
    _step = 0;
}

// ── ON ──
// HYBRID sequence: TRIAC fires first (carries the inrush current),
// relay closes after settle, then the TRIAC gate is released.
// RELAY/TRIAC modes switch the respective element immediately.
void MainsSwitch::turnOn() {
    if (_on) return;  // already fully on

    _on = true;
    _onTime = millis();

    if (_mode == Mode::RELAY) {
        _driveRelay(true);
        return;
    }
    if (_mode == Mode::TRIAC) {
        _driveTriac(true);
        return;
    }

    // HYBRID
    if (_step == 3) {
        // Was turning OFF: relay still closed, TRIAC already re-fired
        // -> skip firing, just settle then release the TRIAC
        _step = 2;
        _lastAction = millis();
        return;
    }
    if (_step == 4) {
        // Was turning OFF: relay already open, TRIAC still conducting
        // -> close the relay now, TRIAC released after settle
        _driveRelay(true);
        _step = 2;
        _lastAction = millis();
        return;
    }

    // Fresh start
    _step = 1;
    _lastAction = millis();
    _driveTriac(true);
}

// ── OFF ──
// HYBRID sequence: TRIAC re-fires (takes over the load current), relay
// opens after settle, then the TRIAC gate is released (everything off).
void MainsSwitch::turnOff() {
    if (!_on) {
        // Already off: only a pending ON sequence can still be cancelled
        if (_step == 0 || _step >= 3) return;
    }
    _on = false;

    if (_mode == Mode::RELAY) {
        _driveRelay(false);
        return;
    }
    if (_mode == Mode::TRIAC) {
        _driveTriac(false);
        return;
    }

    // HYBRID
    if (_step == 1) {
        // Was turning ON: TRIAC fired but relay never closed
        // -> cancel entirely
        _driveTriac(false);
        _step = 0;
        return;
    }
    if (_step == 2) {
        // Was turning ON: relay closed but TRIAC still conducting
        // -> skip re-fire, just open the relay
        _step = 3;
        _lastAction = millis();
        return;
    }

    // Fresh start (or abort -> restart)
    _step = 3;
    _lastAction = millis();
    _driveTriac(true);
}

bool MainsSwitch::toggle() {
    if (_on) turnOff();
    else turnOn();
    return _on;
}

// Advances the HYBRID switching sequence (call from the main loop).
void MainsSwitch::handle() {
    if (_mode != Mode::HYBRID || _step == 0) return;

    unsigned long now = millis();

    switch (_step) {
        case 1:
            // TRIAC conducting -> close the relay
            if (now - _lastAction >= T_TRIAC_SETTLE) {
                _driveRelay(true);
                _lastAction = now;
                _step = 2;
            }
            break;
        case 2:
            // Relay closed -> release the TRIAC gate
            if (now - _lastAction >= T_RELAY_SETTLE) {
                _driveTriac(false);
                _step = 0;
            }
            break;
        case 3:
            // TRIAC re-fired -> open the relay
            if (now - _lastAction >= T_TRIAC_SETTLE) {
                _driveRelay(false);
                if (_onDurationCb) {
                    _onDurationCb(now - _onTime);
                }
                _lastAction = now;
                _step = 4;
            }
            break;
        case 4:
            // Relay open -> release the TRIAC gate
            if (now - _lastAction >= T_RELAY_SETTLE) {
                _driveTriac(false);
                _step = 0;
            }
            break;
    }
}

unsigned long MainsSwitch::getOnDuration() const {
    if (!_on) return 0;
    return millis() - _onTime;
}

void MainsSwitch::setOnDurationCallback(std::function<void(unsigned long)> cb) {
    _onDurationCb = cb;
}