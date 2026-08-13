#pragma once

#include <Arduino.h>
#include <functional>

// MainsSwitch — switches AC mains power for a wall outlet (WiFi smart socket).
//
// Supports three switching modes:
//   RELAY  : electromechanical relay only (audible click, no TRIAC needed)
//   TRIAC  : TRIAC only (silent solid-state switching, no relay needed)
//   HYBRID : TRIAC pre-fires first, relay closes after settle, then the TRIAC
//            gate is released — arc-free and nearly silent while the relay
//            stays as the final current-carrying contact.
//
// "Active low" means the pin is pulled LOW to energize the element, which is
// common on transistor/optocoupler-driven boards.
class MainsSwitch {
public:
    enum class Mode : uint8_t {
        RELAY,   // relay only
        TRIAC,   // TRIAC only
        HYBRID   // TRIAC + relay (arc-free sequence)
    };

    MainsSwitch();

    // Pass -1 for an element that is not present on the board.
    // Mode is auto-selected: both pins -> HYBRID, relay only -> RELAY,
    // TRIAC only -> TRIAC; override it later with setMode().
    void begin(int relayPin, int triacGatePin,
               bool relayActiveLow = true, bool triacActiveLow = true);

    // Overtides the auto-selected mode. A mode that needs a missing pin
    // (-1) falls back to the auto-selected mode.
    void setMode(Mode mode);
    Mode getMode() const { return _mode; }

    void turnOn();
    void turnOff();
    bool toggle();
    bool getState() const { return _on; }

    // Call in loop(): completes the delayed HYBRID switching sequence.
    void handle();

    unsigned long getOnDuration() const;  // ms since the load was turned on
    void setOnDurationCallback(std::function<void(unsigned long)> cb);

    int getRelayPin() const { return _relayPin; }
    int getTriacGatePin() const { return _triacPin; }

private:
    int _relayPin;
    int _triacPin;
    bool _relayActiveLow;
    bool _triacActiveLow;
    Mode _mode;

    bool _on;        // requested/logical output state
    bool _triacOn;   // TRIAC gate currently energized
    unsigned long _onTime;
    unsigned long _lastAction;
    int _step;
    // HYBRID sequence steps:
    // 0 = idle, 1 = ON: TRIAC fired, waiting -> close relay,
    // 2 = ON: relay closed, waiting -> release TRIAC,
    // 3 = OFF: TRIAC re-fired, waiting -> open relay,
    // 4 = OFF: relay open, waiting -> release TRIAC

    static constexpr unsigned long T_TRIAC_SETTLE = 99;  // ms
    static constexpr unsigned long T_RELAY_SETTLE = 99;  // ms

    std::function<void(unsigned long)> _onDurationCb;

    void _driveRelay(bool on);
    void _driveTriac(bool on);
    void _interruptSequence();
};