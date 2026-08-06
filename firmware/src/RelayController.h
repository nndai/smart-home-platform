#pragma once

#include <Arduino.h>
#include <functional>

class RelayController {
public:
    RelayController();
    void begin(int relayPin, int triacGatePin);
    void turnOn();
    void turnOff();
    bool getState() const { return _on; }
    bool toggle();
    void handle();  // call in loop for delayed operations
    unsigned long getOnDuration() const;  // ms since turn on
    void setOnDurationCallback(std::function<void(unsigned long)> cb);

    int getRelayPin() const { return _relayPin; }
    int getTriacGatePin() const { return _triacGatePin; }
private:
    int _relayPin;
    int _triacGatePin;
    bool _on;
    bool _triacOn;
    unsigned long _onTime;
    unsigned long _lastAction;
    int _step;
    // 0=idle, 1=ON:triac-fired→relay-close, 2=ON:relay-closed→triac-off,
    // 3=OFF:triac-fired→relay-open, 4=OFF:relay-open→triac-off

    static constexpr unsigned long T_TRIAC_SETTLE = 99;
    static constexpr unsigned long T_RELAY_SETTLE = 99;

    std::function<void(unsigned long)> _onDurationCb;
    void _triacFire();
    void _triacOff();
};
