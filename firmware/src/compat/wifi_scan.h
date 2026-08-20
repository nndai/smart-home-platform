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

// ESP8266: Use scanNetworksAsync
inline void scanAsync(std::function<void()> onDone) {
    static std::function<void()> s_onDone;
    s_onDone = onDone;
    WiFi.scanDelete();
    WiFi.scanNetworksAsync([](int count) {
        (void)count;
        if (s_onDone) s_onDone();
    });
}

#elif defined(LT_ARD_HAS_SERIAL)
// LibreTiny (LN882H)
inline void scanAsync(std::function<void()> onDone) {
    static std::function<void()> s_onDone;
    static auto _scanEventHandlerId = WiFi.onEvent([](EventId event, EventInfo info) {
        (void)event; (void)info;
        if (s_onDone) s_onDone();
    });
    (void)_scanEventHandlerId; // silence unused warning
    s_onDone = onDone;
    chip::scanStart();
}

#else
// ESP32
inline void scanAsync(std::function<void()> onDone) {
    static std::function<void()> s_onDone;
    static auto _scanEventHandlerId = WiFi.onEvent([](arduino_event_id_t event, arduino_event_info_t info) {
        (void)event; (void)info;
        if (s_onDone) s_onDone();
    }, ARDUINO_EVENT_WIFI_SCAN_DONE);
    (void)_scanEventHandlerId; // silence unused warning
    s_onDone = onDone;
    chip::scanStart();
}

#endif

}
