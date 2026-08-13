#pragma once

// ── Pins riêng của profile SWITCH (ESP32) ──
// Được include bởi include/Config.h khi build với -DPROFILE_SWITCH.

#define PIN_RELAY          4   // GPIO4  - Relay control (ESP32 dev)
#define PIN_RELAY_ACTIVE_LOW  0  // 1 = LOW kích relay, 0 = HIGH kích relay
#define PIN_LED            2   // GPIO2  - Status LED (active LOW)
#define PIN_BUTTON         0   // GPIO0  - Push button (active LOW, pull-up)
