#pragma once

#include <Arduino.h>

namespace chip {
struct ScanResult {
    char ssid[33];
    int32_t rssi;
    uint8_t bssid[6];
    bool isEncrypt;
};

// Bắt đầu scan WiFi bất đồng bộ. Trả về 0 nếu thành công.
// LN882H: wifi_softap_scan (SDK) — scan ngay trong AP mode, AP không bị tắt.
// MCU khác: WiFi.scanNetworks (async).
int scanStart();

// Đọc kết quả scan WiFi gần nhất (tối đa maxCount phần tử). Trả về số lượng.
int scanGetResults(ScanResult* out, int maxCount);

// Số lượng mạng quét được (-1: đang quét / chưa sẵn sàng).
int scanGetScanCount();

// LN882H: gọi định kỳ từ task thường (taskWsLoop). Nếu scan đã hoàn tất,
// chuyển event SCAN_DONE qua WiFi.postEvent — callback của lib chỉ đánh
// dấu semaphore, không gọi postEvent trực tiếp (deadlock/WDT trong wifi task).
void scanPumpDoneEvent();
}
