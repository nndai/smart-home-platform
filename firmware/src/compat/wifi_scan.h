#pragma once

#include <WiFi.h>

namespace compat {
// Bắt đầu scan WiFi bất đồng bộ. ESP8266 chỉ nhận 2 tham số scanNetworks.
inline bool scanStart() {
#if defined(ARDUINO_ARCH_ESP8266)
    return WiFi.scanNetworks(true) >= 0;
#else
    WiFi.scanDelete();
    return WiFi.scanNetworks(true, false, false, 200) >= 0;
#endif
}

inline int16_t scanComplete() {
    return WiFi.scanComplete();
}

// ESP8266 không có WiFi.scanDelete() → no-op
inline void scanDelete() {
#if !defined(ARDUINO_ARCH_ESP8266)
    WiFi.scanDelete();
#endif
}
}
