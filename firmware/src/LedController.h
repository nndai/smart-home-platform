#ifndef LEDCONTROLLER_H
#define LEDCONTROLLER_H

#include <Arduino.h>

class LedController {
private:
    enum LedState {
        OFF = 0,
        ON,
        BLINK,
        BLINK_N,
    };

    uint8_t _pin;
    uint8_t _isOn;
    uint8_t _activeLow;
    uint32_t _blinkInterval;
    uint32_t _lastToggleTime;
    LedState _state;
    uint8_t _blinkCount;
    uint8_t _blinkIndex;
    uint32_t _onInterval;
    uint32_t _offInterval;

public:
    LedController()
        : _pin(0), _isOn(0), _activeLow(true), _blinkInterval(0), _lastToggleTime(0),
        _state(OFF), _blinkCount(0), _blinkIndex(0), _onInterval(0), _offInterval(0) {}

    void begin(uint8_t pin, bool activeLow = true) {
        _pin = pin;
        _activeLow = activeLow;
        pinMode(_pin, OUTPUT);
        digitalWrite(_pin, _activeLow ? HIGH : LOW);
    }

    void blink(uint32_t intervalMs) {
        if (_state == BLINK && intervalMs == _blinkInterval)
            return;
        _blinkInterval = intervalMs;
        _state = BLINK;
        _lastToggleTime = millis();
    }

    void blink(uint8_t blinkCount, uint32_t onIntervalMs, uint32_t offIntervalMs) {
        if (_state == BLINK_N) {
            if (blinkCount == _blinkCount && onIntervalMs == _onInterval && offIntervalMs == _offInterval)
                return;
        }
        _state = BLINK_N;
        _blinkCount = blinkCount;
        _blinkIndex = 0;
        _onInterval = onIntervalMs;
        _offInterval = offIntervalMs;
        _lastToggleTime = millis();
    }

    void on() {
        if (_state == ON) return;
        _state = ON;
        _isOn = 1;
        _blinkInterval = 0;
        digitalWrite(_pin, _activeLow ? LOW : HIGH);
    }

    void off() {
        if (_state == OFF) return;
        _state = OFF;
        _isOn = 0;
        _blinkInterval = 0;
        digitalWrite(_pin, _activeLow ? HIGH : LOW);
    }

    void update() {
        uint32_t currentTime = millis();
        if (_state == BLINK_N) {
            if (_blinkIndex < _blinkCount) {
                if (!_isOn && currentTime - _lastToggleTime >= _onInterval) {
                    _isOn = true;
                    digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                    _lastToggleTime = currentTime;
                }
                else if (_isOn && currentTime - _lastToggleTime >= _onInterval) {
                    _isOn = false;
                    digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                    _lastToggleTime = currentTime;
                    _blinkIndex++;
                }
            }
            else {
                if (currentTime - _lastToggleTime >= _offInterval) {
                    _blinkIndex = 0;
                }
            }
        }
        else if (_state == BLINK) {
            if (currentTime - _lastToggleTime >= _blinkInterval) {
                _isOn = !_isOn;
                digitalWrite(_pin, (_isOn ^ _activeLow) ? HIGH : LOW);
                _lastToggleTime = currentTime;
            }
        }
    }
};

#endif
