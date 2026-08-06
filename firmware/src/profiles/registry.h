#pragma once

#include "core/DeviceDriver.h"

// ── Chọn driver theo profile (nơi #ifdef duy nhất của toàn project) ──
#if defined(PROFILE_PUMP)
#include "profiles/pump/PumpDriver.h"
// #elif defined(PROFILE_SWITCH)
// #include "profiles/switch/SwitchDriver.h"
#endif

inline DeviceDriver* createDriver() {
#if defined(PROFILE_PUMP)
    return new PumpDriver();
// #elif defined(PROFILE_SWITCH)
//     return new SwitchDriver();
#endif
    return nullptr;
}
