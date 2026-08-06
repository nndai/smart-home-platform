#pragma once

#include <Arduino.h>

namespace chip {
struct ScanResult {
    char ssid[33];
    int32_t rssi;
    uint8_t bssid[6];
    bool isEncrypt;
};

// Đọc kết quả scan WiFi gần nhất (tối đa maxCount phần tử). Trả về số lượng.
int scanGetResults(ScanResult* out, int maxCount);
}
