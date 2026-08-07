#pragma once

#include <Arduino.h>

namespace chip {
// LN882H: chân relay/triac trùng chân bootloader → phải reclaim khỏi SDK.
// MCU khác: no-op.
void reclaimRelayGpio();

// Nhiệt độ chip WiFi (LN882H đọc ADC_CH0; MCU khác trả 0)
float readWifiTempC();

// ── Thông tin hệ thống (getSystemInfo của CommandHandler) ──
const char* chipModelName();      // LT: lt_cpu_get_model_code(); ESP32: ESP.getChipModel(); ESP8266: "ESP8266"
uint32_t systemChipId();          // LT: ESP.getChipId(); ESP32: từ eFuse MAC
const char* systemResetReason();  // LT: ESP.getResetReason(); ESP32: esp_reset_reason_str()
size_t heapMinFree();             // LT: lt_heap_get_min_free(); ESP32: heap_caps minimum
}
