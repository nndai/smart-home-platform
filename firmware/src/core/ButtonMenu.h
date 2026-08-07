#pragma once

#include <Arduino.h>
#include <functional>

#include "core/LedController.h"

// ── Menu giữ nút dùng chung (cơ chế) ──
// Mỗi profile (pump, fan...) tự định nghĩa danh sách bước + hành vi riêng.
// Nhịp: start() khi phát hiện long-press, tick() gọi mỗi vòng lặp (~20ms).
class ButtonMenu {
public:
    struct Step {
        const char* name;
        std::function<void()> action;
    };

    void begin(LedController* led, const Step* steps, size_t count,
               uint32_t holdMs, uint32_t confirmMs);
    void start();
    void tick(bool buttonPressed);
    bool isActive() const { return _stage != Stage::IDLE; }

private:
    enum class Stage {
        IDLE,
        WAIT_HOLD,
        CONFIRM,
    };

    void confirm();

    LedController* _led = nullptr;
    const Step* _steps = nullptr;
    size_t _count = 0;
    uint32_t _holdMs = 0;
    uint32_t _confirmMs = 0;
    size_t _step = 0;
    Stage _stage = Stage::IDLE;
    uint32_t _stageStart = 0;
};
