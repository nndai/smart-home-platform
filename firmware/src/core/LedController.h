#ifndef LEDCONTROLLER_H
#define LEDCONTROLLER_H

#include <Arduino.h>
#include "chip/io.h"

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
    
    uint8_t _brightness;
    bool _usePwm;

    void _applyState(bool on) {
        if (!on) {
            digitalWrite(_pin, _activeLow ? HIGH : LOW);
        } else {
            if (!_usePwm || _brightness == 100) {
                digitalWrite(_pin, _activeLow ? LOW : HIGH);
            } else {
                chip::writePwm(_pin, _brightness, _activeLow);
            }
        }
    }

public:
    LedController()
        : _pin(0), _isOn(0), _activeLow(true), _blinkInterval(0), _lastToggleTime(0),
        _state(OFF), _blinkCount(0), _blinkIndex(0), _onInterval(0), _offInterval(0),
        _brightness(100), _usePwm(false) {}

    void setBrightness(uint8_t percentage) {
        if (percentage > 100) percentage = 100;
        _brightness = percentage;
        _usePwm = true;
        // Cập nhật ngay nếu đang bật
        if (_isOn) _applyState(true);
    }

    void disablePwm() {
        _usePwm = false;
        _brightness = 100;
        if (_isOn) _applyState(true);
    }

    void begin(uint8_t pin, bool activeLow = true) {
        _pin = pin;
        _activeLow = activeLow;
        pinMode(_pin, OUTPUT);
        _applyState(false);
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
        _applyState(true);
    }

    void off() {
        if (_state == OFF) return;
        _state = OFF;
        _isOn = 0;
        _blinkInterval = 0;
        _applyState(false);
    }

    void update() {
        uint32_t currentTime = millis();
        if (_state == BLINK_N) {
            if (_blinkIndex < _blinkCount) {
                if (!_isOn && currentTime - _lastToggleTime >= _onInterval) {
                    _isOn = true;
                    _applyState(true);
                    _lastToggleTime = currentTime;
                }
                else if (_isOn && currentTime - _lastToggleTime >= _onInterval) {
                    _isOn = false;
                    _applyState(false);
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
                _applyState(_isOn);
                _lastToggleTime = currentTime;
            }
        }
    }
};

#endif
