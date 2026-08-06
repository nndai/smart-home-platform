#pragma once

#include <Arduino.h>

namespace chip {
// LN882H: chân relay/triac trùng chân bootloader → phải reclaim khỏi SDK.
// MCU khác: no-op.
void reclaimRelayGpio();

// Nhiệt độ chip WiFi (LN882H đọc ADC_CH0; MCU khác trả 0)
float readWifiTempC();
}
