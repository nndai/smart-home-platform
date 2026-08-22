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

#elif defined(ARDUINO_ARCH_ESP8266)
#include "user_interface.h"
#include "eagle_soc.h"

// Factory 128-bit eFuse OTP (immutable, verified by hardware test):
// registers 0x3FF00050..0x3FF0005C hold MAC, chip ID and factory flags,
// layout matches the esptool.py 128-bit read. Contains the 48-bit MAC,
// so the value is unique per chip.
static void readEfuse128(uint8_t out[16]) {
    uint32_t words[4] = {
        READ_PERI_REG(0x3ff00050),
        READ_PERI_REG(0x3ff00054),
        READ_PERI_REG(0x3ff00058),
        READ_PERI_REG(0x3ff0005c),
    };
    for (int i = 0; i < 4; i++) {
        out[i * 4 + 0] = (words[i] >> 0) & 0xff;
        out[i * 4 + 1] = (words[i] >> 8) & 0xff;
        out[i * 4 + 2] = (words[i] >> 16) & 0xff;
        out[i * 4 + 3] = (words[i] >> 24) & 0xff;
    }
}

namespace chip {

    size_t anchorBytes(uint8_t out[16]) {
        readEfuse128(out);
        return 16;
    }

    void randomBytes(uint8_t* out, size_t len) {
        os_get_random(out, len);
    }
}
#else
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
