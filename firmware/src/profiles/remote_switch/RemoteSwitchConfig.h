#pragma once

#include "core/ConfigManager.h"
#include <string.h>

struct RemoteSwitchConfig : public DeviceConfig {
    char targetId[32] = "";
    char targetKey[65] = ""; // Target control key (hex string is 64 chars + null)
    char targetType[16] = ""; // "pump" or "switch"
};
