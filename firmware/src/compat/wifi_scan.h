#pragma once

#include "compat/wifi.h"
#include "chip/scan.h"

namespace compat {
// Bắt đầu scan WiFi bất đồng bộ. LN882H: wifi_softap_scan (SDK) — AP giữ nguyên.
inline bool scanStart() {
    return chip::scanStart() == 0;
}

inline int16_t scanComplete() {
    return chip::scanGetScanCount();
}

inline void scanDelete() {
    WiFi.scanDelete();
}


#if defined(ARDUINO_ARCH_ESP8266)
typedef int arduino_event_id_t;
typedef int arduino_event_info_t;
#ifndef ARDUINO_EVENT_WIFI_SCAN_DONE
#define ARDUINO_EVENT_WIFI_SCAN_DONE 0
#endif
#endif

}
