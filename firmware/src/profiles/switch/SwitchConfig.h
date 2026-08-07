#pragma once

#include "core/ConfigManager.h"

// ── Config riêng của profile SWITCH ──
struct SwitchConfig : public DeviceConfig {
    // Profile switch chưa có field riêng — dùng chung DeviceConfig.

    // ── Relay startup mode (OFF=0 / ON=1 / LAST=2) ──
    RelayStartMode relayStartMode = RelayStartMode::OFF;
    // Chỉ append field mới ở cuối struct, không chèn giữa.
};
