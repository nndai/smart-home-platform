#pragma once

#include "core/ConfigManager.h"
#include <string.h>

struct RemoteSwitchConfig : public DeviceConfig {
    char targetId[32] = "";
    uint8_t targetKey[32] = {0}; // 32-byte raw target control key
    bool hasTargetKey = false;
    char targetType[16] = ""; // "pump" or "switch"
};
