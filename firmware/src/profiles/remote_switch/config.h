#pragma once

// ── Pins + tham số riêng của profile REMOTE_SWITCH ──
// Được include bởi include/Config.h khi build với -DPROFILE_REMOTE_SWITCH.

#if defined(ARDUINO_ARCH_ESP8266)
// ESP8266 NodeMCU: GPIO2 = D4, GPIO5 = D1, GPIO12 = D6 (flash button = GPIO0/D3)
// 4 đèn, tất cả active HIGH, xanh/đỏ trong mỗi cặp loại trừ nhau bằng code:
//   Cặp 1 (kết nối): đỏ = lỗi wifi/mqtt/ntp/timeout status, xanh = khỏe mạnh
//   Cặp 2 (trạng thái): xanh = on/waiting, đỏ = error
#define PIN_LED_CONNECT_RED   14 // GPIO14 (D5) - LED đỏ: mất kết nối (active HIGH)
#define PIN_LED_CONNECT_GREEN 12 // GPIO12 (D6) - LED xanh: kết nối khỏe mạnh (active HIGH)
#define PIN_LED_STATE_GREEN    2 // GPIO2  (D4) - LED xanh: target on/waiting (active HIGH)
#define PIN_LED_STATE_RED     13 // GPIO13 (D7) - LED đỏ: target error (active HIGH)
#define PIN_BUTTON             5 // GPIO5  (D1) - nút nhấn (active LOW, pull-up)

#define OTA_BTN_PIN             PIN_BUTTON
#define OTA_BTN_ACTIVE_LOW      true
#define OTA_LED_PIN             PIN_LED_STATE_GREEN
#define OTA_LED_ACTIVE_LOW      false // LED active HIGH (xem RemoteSwitchDriver::begin)
#else
#error "PROFILE_REMOTE_SWITCH: no pin table for this platform yet — add it in profiles/remote_switch/config.h"
#endif