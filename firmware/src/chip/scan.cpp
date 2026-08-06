#include "chip/scan.h"

// ── LibreTiny: đọc danh sách AP từ wifi_manager (raw SDK) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>
#include <string.h>

namespace chip {
int scanGetResults(ScanResult* out, int maxCount) {
    ln_list_t* list = NULL;
    uint8_t apCount = 0;
    if (wifi_manager_get_ap_list(&list, &apCount) != 0 || !list) return 0;

    int idx = 0;
    ap_info_node_t* pnode;
    LN_LIST_FOR_EACH_ENTRY(pnode, ap_info_node_t, list, list) {
        if (idx >= maxCount) break;
        ap_info_t* ap = &pnode->info;
        strncpy(out[idx].ssid, ap->ssid, sizeof(out[idx].ssid) - 1);
        out[idx].ssid[sizeof(out[idx].ssid) - 1] = '\0';
        out[idx].rssi = ap->rssi;
        memcpy(out[idx].bssid, ap->bssid, 6);
        out[idx].isEncrypt = (ap->authmode != 0);
        idx++;
    }
    return idx;
}
}

// ── MCU khác: WiFi.scanResult(i) (API Arduino chuẩn) ──
#else
#include <WiFi.h>

namespace chip {
int scanGetResults(ScanResult* out, int maxCount) {
    int16_t count = WiFi.scanComplete();
    if (count < 0) return 0;
    int n = count < maxCount ? count : maxCount;
    for (int i = 0; i < n; i++) {
        auto info = WiFi.scanResult(i);
        strncpy(out[i].ssid, info.SSID.c_str(), sizeof(out[i].ssid) - 1);
        out[i].ssid[sizeof(out[i].ssid) - 1] = '\0';
        out[i].rssi = info.RSSI;
        memcpy(out[i].bssid, info.BSSID, 6);
        out[i].isEncrypt = (info.encryptionType != WIFI_AUTH_OPEN);
    }
    return n;
}
}
#endif
