#pragma once

// ── WiFi header đa nền tảng ──
#if defined(ARDUINO_ARCH_ESP8266)
#include <ESP8266WiFi.h>
#else
#include <WiFi.h>
#endif

namespace compat {
    
inline void wifiConfigureSleep() {
#if defined(LT_ARD_HAS_SERIAL)
    WiFi.setSleep(true);
#elif defined(ARDUINO_ARCH_ESP8266)
    WiFi.setSleepMode(WIFI_NONE_SLEEP);
#endif
}
}
