#pragma once

#include <Arduino.h>

// ── LibreTiny: WDT.h (lt_wdt_*) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <WDT.h>

namespace compat {
inline bool wdtEnable(uint32_t timeout) { return WDT.enable(timeout); }
inline void wdtFeed() { WDT.feed(); }
}

// ── MCU khác (ESP8266): ESP.wdtEnable / ESP.wdtFeed ──
#elif defined(ARDUINO_ARCH_ESP8266)

namespace compat {
// inline bool wdtEnable(uint32_t timeout) { (void)timeout; ESP.wdtEnable(WDTO_8S); return true; }
// inline void wdtFeed() { ESP.wdtFeed(); }
    inline bool wdtEnable(uint32_t timeout) {return true; }
    inline void wdtFeed() { }
}

// ── MCU khác (ESP32): esp_task_wdt ──
#else
#include "esp_task_wdt.h"

namespace compat {
inline bool wdtEnable(uint32_t timeout) { return esp_task_wdt_init(timeout, true) == ESP_OK; }
inline void wdtFeed() { esp_task_wdt_reset(); }
}
#endif
