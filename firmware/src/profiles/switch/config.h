#pragma once

// ── Pins riêng của profile SWITCH (ESP32) ──
// Được include bởi include/Config.h khi build với -DPROFILE_SWITCH.

#define PIN_RELAY          4   // GPIO4  - Relay control (ESP32 dev)
#define PIN_RELAY_ACTIVE_LOW  0  // 1 = LOW kích relay, 0 = HIGH kích relay
#define PIN_LED            2   // GPIO2  - Status LED (active LOW)
#define PIN_BUTTON         0   // GPIO0  - Push button (active LOW, pull-up)

#define OTA_LED_PIN         PIN_LED  // LED nhấp nháy khi OTA đang chạy
#define OTA_LED_ACTIVE_LOW  true     // LED nhấp nháy khi OTA đang chạy
#define OTA_BTN_PIN         PIN_BUTTON  // Nhấn nút để kích hoạt OTA
#define OTA_BTN_ACTIVE_LOW  true     // Nút OTA hoạt động ở mức thấp
