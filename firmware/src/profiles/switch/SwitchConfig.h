#pragma once

#include "core/ConfigManager.h"

// ── Config riêng của profile SWITCH ──
struct SwitchConfig : public DeviceConfig {
    // Profile switch chưa có field riêng — dùng chung DeviceConfig.
    // Chỉ append field mới ở cuối struct, không chèn giữa.
};
