#include "chip/scan.h"

// ── LibreTiny (LN882H): scan trong AP mode bằng wifi_softap_scan (raw SDK) ──
// AP+STA không hỗ trợ → không thể dùng WiFi.scanNetworks khi đang ở AP.
// wifi_softap_scan() là API SDK scan ngay khi AP đang bật (AT firmware dùng
// AT+AP_SCAN): không tắt AP, không chuyển mode → phone không mất kết nối.
// Callback hoàn tất chạy trong wifi lib task (mac_task, stack 2048B,
// priority REAL_TIME). TUYỆT ĐỐI không gọi Arduino WiFi API (postEvent),
// TCP/lwIP hay WS ở đây: lib đang tiếp tục công việc wifi (log thấy thêm
// một lượt quét sau "AP Scan completed!"), gọi TCP/lwIP trong context này
// → busy-wait chết đói task wdtFeed (priority idle+1) hoặc deadlock →
// WDT reset toàn chip. Chỉ copy dữ liệu + đánh dấu semaphore; postEvent
// do scanPumpDoneEvent() thực hiện từ task thường. (Gọi SDK
// wifi_softap_scan_results_get trong callback là OK — đã chứng minh ổn định.)
//
// LƯU Ý ABI: lib prebuilt libln882h_wifi.a được compile với short-enums
// (xem disasm wifi_cfg_protocol_build_ap_scan: `ldrh r3,[r6,#2]` đọc scan_time
// ở offset 2; wifi_softap_scan_results_get: ap_info stride 48, rssi@42),
// còn project build với -fno-short-enums → struct từ wifi.h bị lệch layout.
// Phải dùng struct tự định nghĩa với field 1-byte khớp layout lib (không phải
// #pragma pack — vấn đề là kích thước enum, không phải alignment), không dùng
// wifi_scan_cfg_t/ap_info_t.
#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>
#include <string.h>
#include <WiFi.h>
#include "compat/log.h"
#include "compat/task.h"

namespace chip {

static const int kMaxScanResults = 40;
static ScanResult s_scanResults[kMaxScanResults];
static int s_scanCount = -1;
static SemaphoreHandle_t s_scanDoneSem = nullptr;

// Layout wifi_scan_cfg_t như lib hiểu (short-enums): channel@0, scan_type@1, scan_time@2
struct ScanCfg {
    uint8_t channel;
    uint8_t scan_type;
    uint16_t scan_time;
};

// Layout ap_info_t như lib ghi vào buffer kết quả (stride 48, bssid@0, ssid@6,
// channel@39, authmode@40, imode@41, rssi@42, freq_offset@44, bgn@46, bitfield@47)
struct ApInfoRaw {
    uint8_t bssid[6];
    char ssid[33];
    uint8_t channel;
    uint8_t authmode;
    uint8_t imode;
    int8_t rssi;
    uint8_t reserved;
    int16_t freq_offset;
    uint8_t bgn;
    uint8_t bitfield;
};
static_assert(sizeof(ApInfoRaw) == 48, "ApInfoRaw phai khop layout lib (stride 48)");
static ApInfoRaw s_apBuf[kMaxScanResults];

static void softApScanCb(void* arg) {
    (void)arg;
    ap_info_t* list = NULL;
    int items = 0;
    wifi_softap_scan_results_get(&list, &items);
    LT_IM(NET, "[scan] results_get: list=%p items=%d", (void*)list, items);
    if (list && items > 0) {
        int n = items < kMaxScanResults ? items : kMaxScanResults;
        for (int i = 0; i < n; i++) {
            // TUYỆT ĐỐI không index bằng ap_info_t (&list[i]): header project
            // compile -fno-short-enums → sizeof(ap_info_t)=52, lib ghi stride 48
            // → record ≥1 lệch +4/+8. Dùng ApInfoRaw (48B, khớp layout lib).
            ApInfoRaw* ap = (ApInfoRaw*)list + i;

            strncpy(s_scanResults[i].ssid, ap->ssid, sizeof(s_scanResults[i].ssid) - 1);
            s_scanResults[i].ssid[sizeof(s_scanResults[i].ssid) - 1] = '\0';
            s_scanResults[i].rssi = ap->rssi;
            memcpy(s_scanResults[i].bssid, ap->bssid, 6);
            s_scanResults[i].isEncrypt = (ap->authmode != (uint8_t)WIFI_AUTH_OPEN);
        }
        s_scanCount = n;
    } else {
        s_scanCount = 0;
    }
    LT_IM(NET, "softAP scan done: %d APs", s_scanCount);

    // Không gọi WiFi.postEvent ở đây (xem comment đầu file — chạy trong wifi
    // lib task → deadlock/WDT). Chỉ đánh dấu semaphore; scanPumpDoneEvent()
    // gọi postEvent từ task thường (taskWsLoop).
    if (!s_scanDoneSem) {
        s_scanDoneSem = xSemaphoreCreateBinary();
    }
    if (s_scanDoneSem) {
        xSemaphoreGive(s_scanDoneSem);
    }
}

// Chạy từ task thường (taskWsLoop, sau wsServer.handle()). Nếu scan đã xong,
// gọi WiFi.postEvent đồng bộ — lambda SCAN_DONE chạy trong context task này.
void scanPumpDoneEvent() {
    if (!s_scanDoneSem) return;
    if (xSemaphoreTake(s_scanDoneSem, 0) != pdTRUE) return;

    EventInfo eventInfo;
    memset(&eventInfo, 0, sizeof(EventInfo));
    eventInfo.wifi_scan_done.status = 0;
    eventInfo.wifi_scan_done.number = s_scanCount;
    WiFi.postEvent(ARDUINO_EVENT_WIFI_SCAN_DONE, eventInfo);
}

int scanStart() {
    s_scanCount = -1;
    if (s_scanDoneSem) {
        xSemaphoreTake(s_scanDoneSem, 0);   // drain event cũ từ lần scan trước
    }
    ScanCfg cfg = {};
    cfg.channel = 0;                        // quét tất cả kênh
    cfg.scan_type = (uint8_t)WIFI_SCAN_TYPE_ACTIVE;
    cfg.scan_time = 1000;
    int ret = wifi_softap_scan((wifi_scan_cfg_t*)&cfg, s_apBuf, kMaxScanResults, softApScanCb);
    if (ret != 0) LT_EM(NET, "wifi_softap_scan start failed: %d", ret);
    return ret;
}

int scanGetResults(ScanResult* out, int maxCount) {
    if (!out || maxCount <= 0) return 0;
    int n = (s_scanCount > 0) ? (s_scanCount < maxCount ? s_scanCount : maxCount) : 0;
    for (int i = 0; i < n; i++) out[i] = s_scanResults[i];
    return n;
}

int scanGetScanCount() {
    return s_scanCount;
}
}

// ── MCU khác: API scan Arduino chuẩn ──
#else
#include <WiFi.h>
#include <string.h>

namespace chip {
int scanStart() {
#if defined(ARDUINO_ARCH_ESP8266)
    return WiFi.scanNetworks(true) >= 0 ? 0 : -1;
#else
    WiFi.scanDelete();
    return WiFi.scanNetworks(true, false, false, 200) >= 0 ? 0 : -1;
#endif
}

int scanGetResults(ScanResult* out, int maxCount) {
    int16_t count = WiFi.scanComplete();
    if (count < 0) return 0;
    int n = count < maxCount ? count : maxCount;
    for (int i = 0; i < n; i++) {
#if defined(ARDUINO_ARCH_ESP8266)
        auto info = WiFi.scanResult(i);
        strncpy(out[i].ssid, info.SSID.c_str(), sizeof(out[i].ssid) - 1);
        out[i].rssi = info.RSSI;
        memcpy(out[i].bssid, info.BSSID, 6);
        out[i].isEncrypt = (info.encryptionType != WIFI_AUTH_OPEN);
#else
        String ssid = WiFi.SSID(i);
        strncpy(out[i].ssid, ssid.c_str(), sizeof(out[i].ssid) - 1);
        out[i].rssi = WiFi.RSSI(i);
        uint8_t* bssid = WiFi.BSSID(i);
        if (bssid) memcpy(out[i].bssid, bssid, 6);
        out[i].isEncrypt = (WiFi.encryptionType(i) != WIFI_AUTH_OPEN);
#endif
        out[i].ssid[sizeof(out[i].ssid) - 1] = '\0';
    }
    return n;
}

int scanGetScanCount() {
    return WiFi.scanComplete();
}
}
#endif
