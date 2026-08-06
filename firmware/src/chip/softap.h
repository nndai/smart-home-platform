#pragma once

#include <Arduino.h>

namespace chip {
// Khởi động AP: LN882H dùng SDK path (WPA2 PSK chuẩn), các MCU khác dùng WiFi.softAP()
void softApStart(const char* ssid, const char* pass, uint8_t channel = 1);
}
