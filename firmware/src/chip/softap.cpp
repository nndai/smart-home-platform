#include "chip/softap.h"

#include "compat/log.h"

// ── LibreTiny: SDK path (wifi_softap_start + ln_psk_calc) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <WiFi.h>
#include <sdk_private.h>

namespace chip {

// Struct layout khớp wifi_softap_cfg_t của SDK (không expose trong header C++ công khai)
typedef struct {
    char* ssid;
    char* pwd;
    uint8_t* bssid;
    uint8_t channel;
    uint8_t authmode;
    uint8_t ssid_hidden;
    uint8_t _pad1;          // padding
    uint16_t beacon_interval;
    uint8_t _pad2[2];       // padding
    uint8_t* psk_value;
} ap_cfg_manual_t;

void softApStart(const char* ssid, const char* pass, uint8_t channel) {
    static uint8_t psk[40] = { 0 };
    bool open = (pass == nullptr || pass[0] == '\0');

    if (!open) {
        ln_psk_calc(ssid, pass, psk, sizeof(psk));
    }

    static uint8_t ap_mac[6];
    WiFi.softAPmacAddress(ap_mac);

    static ap_cfg_manual_t ap_cfg;
    ap_cfg.ssid = (char*)ssid;
    ap_cfg.pwd = (char*)(open ? "" : pass);
    ap_cfg.bssid = ap_mac;
    ap_cfg.channel = channel;
    ap_cfg.authmode = open ? 0 : 3; // 0=OPEN, 3=WPA2_PSK
    ap_cfg.beacon_interval = 5000;
    ap_cfg.psk_value = psk;

    int r = wifi_softap_start((wifi_softap_cfg_t*)&ap_cfg);
    if (r != 0) {
        LT_EM(NET, "SoftAP SDK failed: %d, fallback to WiFi.softAP()", r);
        WiFi.softAP(ssid, pass);
    }
}
}

// ── MCU khác: WiFi.softAP() ──
#else
#include <WiFi.h>

namespace chip {
void softApStart(const char* ssid, const char* pass, uint8_t channel) {
    WiFi.softAP(ssid, pass, channel);
}
}
#endif
