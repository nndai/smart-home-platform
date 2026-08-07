#include "chip/anchor.h"

#include <string.h>

// ── LibreTiny (LN882H) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <libretiny.h>
#include <sdk_private.h>

extern "C" void hal_flash_read_unique_id(uint8_t* unique_id);

namespace chip {

    size_t anchorBytes(uint8_t out[16]) {
        hal_flash_read_unique_id(out);
        return 16;
    }

    void randomBytes(uint8_t* out, size_t len) {
        ESP.random(out, len);
    }
}
#endif

// ── MCU khác (ESP32/ESP8266) ──
#if !defined(LT_ARD_HAS_SERIAL)
#include <esp_efuse.h>
#include <esp_random.h>

namespace chip {

    size_t anchorBytes(uint8_t out[16]) {
        uint8_t mac[6];
        esp_efuse_mac_get_default(mac);
        memcpy(out, mac, 6);
        return 6;
    }

    void randomBytes(uint8_t* out, size_t len) {
        esp_fill_random(out, len);  // hardware RNG IDF
    }
}
#endif
