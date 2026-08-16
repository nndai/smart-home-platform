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
#elif defined(PROFILE_REMOTE_SWITCH)
#include "profiles/remote_switch/RemoteSwitchConfig.h"
#include "profiles/remote_switch/RemoteSwitchDriver.h"
using ProfileConfig = RemoteSwitchConfig;
#else
#error "Phai define PROFILE_PUMP hoac PROFILE_SWITCH hoac PROFILE_REMOTE_SWITCH trong build_flags"
#endif

inline DeviceDriver* createDriver() {
#if defined(PROFILE_PUMP)
    return new PumpDriver();
#elif defined(PROFILE_SWITCH)
    return new SwitchDriver();
#elif defined(PROFILE_REMOTE_SWITCH)
    return new RemoteSwitchDriver();
#endif
    return nullptr;
}

// Tên profile (chuỗi công khai, gửi trong getConfig/pair)
inline const char* profileName() {
#if defined(PROFILE_PUMP)
    return "pump";
#elif defined(PROFILE_SWITCH)
    return "switch";
#elif defined(PROFILE_REMOTE_SWITCH)
    return "remote_switch";
#else
    return "unknown";
#endif
}
