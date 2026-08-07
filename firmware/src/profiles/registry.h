#pragma once

#include "core/DeviceDriver.h"

// ── Chọn config type + driver theo profile (nơi #ifdef duy nhất của project) ──
#if defined(PROFILE_PUMP)
#include "profiles/pump/PumpConfig.h"
#include "profiles/pump/PumpDriver.h"
using ProfileConfig = PumpConfig;
#elif defined(PROFILE_SWITCH)
#include "profiles/switch/SwitchConfig.h"
#include "profiles/switch/SwitchDriver.h"
using ProfileConfig = SwitchConfig;
#else
#error "Phai define PROFILE_PUMP hoac PROFILE_SWITCH trong build_flags"
#endif

inline DeviceDriver* createDriver() {
#if defined(PROFILE_PUMP)
    return new PumpDriver();
#elif defined(PROFILE_SWITCH)
    return new SwitchDriver();
#endif
    return nullptr;
}

// Tên profile (chuỗi công khai, gửi trong getConfig/pair)
inline const char* profileName() {
#if defined(PROFILE_PUMP)
    return "pump";
#elif defined(PROFILE_SWITCH)
    return "switch";
#else
    return "unknown";
#endif
}
