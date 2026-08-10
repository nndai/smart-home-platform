#pragma once

#include <WiFi.h>
#include "chip/scan.h"

namespace compat {
// Bắt đầu scan WiFi bất đồng bộ. LN882H: wifi_softap_scan (SDK) — AP giữ nguyên.
inline bool scanStart() {
    return chip::scanStart() == 0;
}

inline int16_t scanComplete() {
    return chip::scanGetScanCount();
}

// ESP8266 không có WiFi.scanDelete() → no-op
inline void scanDelete() {
#if !defined(ARDUINO_ARCH_ESP8266) && !defined(LT_ARD_HAS_SERIAL)
    WiFi.scanDelete();
#endif
}

// LN882H: AP không bao giờ bị tắt khi scan → no-op. MCU khác: no-op.
inline void scanRestore() {}

// LN882H giờ scan ngay trong AP mode (wifi_softap_scan) → AP không rớt.
// App không bị mất kết nối WiFi khi scan trên mọi MCU.
inline bool scanWillDrop() {
    return false;
}
}
