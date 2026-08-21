#pragma once

#include <Arduino.h>

namespace chip {
// LN882H: chân relay/triac trùng chân bootloader → phải reclaim khỏi SDK.
// MCU khác: no-op.
void reclaimRelayGpio();

// Nhiệt độ chip WiFi (LN882H đọc ADC_CH0; MCU khác trả 0)
float readWifiTempC();

// Thiết lập độ sáng PWM cho LED (percent: 0-100), tự xử lý activeLow và phân giải PWM
void writePwm(uint8_t pin, uint8_t percent, bool activeLow);

// ── Thông tin hệ thống (getSystemInfo của CommandHandler) ──
// Trả về String (bản sao) — tránh dangling pointer của String tạm.
String chipModelName();          // LT: lt_cpu_get_model_code() in hoa; ESP32: ESP.getChipModel(); ESP8266: "ESP8266"
uint32_t systemChipId();          // LT: ESP.getChipId(); ESP32: từ eFuse MAC
String systemResetReason();       // LT: ESP.getResetReason(); ESP32: esp_reset_reason_str()
size_t heapMinFree();             // LT: lt_heap_get_min_free(); ESP32: heap_caps minimum
}
