#include "core/ButtonMenu.h"

void ButtonMenu::begin(LedController* led, const Step* steps, size_t count,
                       uint32_t holdMs, uint32_t confirmMs) {
    _led = led;
    _steps = steps;
    _count = count;
    _holdMs = holdMs;
    _confirmMs = confirmMs;
    _stage = Stage::IDLE;
    _step = 0;
}

void ButtonMenu::start() {
    _step = 0;
    _stage = Stage::WAIT_HOLD;
    _stageStart = millis();
    if (_led) {
        _led->blink(100);
    }
}

void ButtonMenu::tick(bool buttonPressed) {
    if (_stage == Stage::IDLE) {
        return;
    }

    uint32_t now = millis();

    if (_stage == Stage::WAIT_HOLD) {
        if (!buttonPressed) {
            confirm();
        }
        else if (now - _stageStart >= _holdMs) {
            _stage = Stage::CONFIRM;
            _stageStart = now;
            if (_led) {
                _led->off();
            }
        }
    }
    else if (_stage == Stage::CONFIRM) {
        if (!buttonPressed) {
            confirm();
        }
        else if (now - _stageStart >= _confirmMs) {
            _step++;
            _stage = Stage::WAIT_HOLD;
            _stageStart = now;
            if (_led) {
                _led->blink((uint32_t)_step * 200);
            }
        }
    }
}

void ButtonMenu::confirm() {
    _stage = Stage::IDLE;
    if (_led) {
        _led->off();
    }
    if (_step < _count && _steps[_step].action) {
        _steps[_step].action();
    }
}
