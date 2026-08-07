#ifndef CONFIG_H
#define CONFIG_H

// ── Pins + tham số riêng theo profile (build_flags: -DPROFILE_PUMP / -DPROFILE_SWITCH) ──
#if defined(PROFILE_PUMP)
#include "profiles/pump/pins.h"
#elif defined(PROFILE_SWITCH)
#include "profiles/switch/pins.h"
#else
#error "Phai define PROFILE_PUMP hoac PROFILE_SWITCH trong build_flags"
#endif

// ── Network ──
#define WEBSOCKET_PORT      82

// ── File Paths (LittleFS) ──
#define PATH_CONFIG_FILE    "/config.json"
#define PATH_LOG_DIR        "/logs/"
#define PATH_LOG_FILE       "/logs/log.txt"

// ── Default WiFi AP ──
#define DEFAULT_AP_SSID     "REMOTE PUMP"
#define DEFAULT_AP_PASSWORD "12345678"

// ── Default WiFi DEBUG ──
#define DEFAULT_DEBUG_SSID      "DESKTOP-P5540"
#define DEFAULT_DEBUG_PASSWORD  "aaaaaaaa"
#define DEFAULT_DEBUG_IP        {192, 168, 137, 111}
#define DEFAULT_DEBUG_GATEWAY   {192, 168, 137, 1}
#define DEFAULT_DEBUG_NETMASK   {255, 255, 255, 0}

// ── System / RTOS ──
#define WDT_TIMEOUT_MS            15000   // watchdog timeout
#define WDT_FEED_INTERVAL_MS      2000    // task wdtFeed feed mỗi 2s
#define HEAP_CRITICAL_BYTES       4096    // heap dưới mức này -> restart
#define STREAM_DURATION_MS        120000  // thời lượng stream status/sysinfo (WS/MQTT)
#define EPOCH_VALID_MIN           1700000000  // epoch >= mức này mới coi là đã đồng bộ giờ

// ── Button ──
#define BUTTON_ACTIVE_LOW          true    // nút nhấn xuống mức LOW (pull-up)
#define BUTTON_LONG_PRESS_MS      5000    // giữ 5s để mở chuỗi thao tác; giữ thêm 5s -> bước kế
#define BUTTON_CONFIRM_TIMEOUT_MS 3000    // nhả nút trong 3s để xác nhận bước đã chọn
#define BUTTON_DEBOUNCE_MS        50

// ── LED ──
#define LED_ACTIVE_LOW             true    // LED sáng ở mức LOW (active low)

// ── MQTT ──
#define DEFAULT_MQTT_PORT          1883
#define DEFAULT_MQTT_TOPIC         "pump"
#define MQTT_BUFFER_SIZE           5000
#define MQTT_SOCKET_TIMEOUT_SEC    7
#define MQTT_RECONNECT_INTERVAL_MS 5000  // khoảng cách giữa 2 lần thử kết nối lại


// ── OTA khẩn cấp bằng tay (OtaBootGuard, xem src/chip/ota_bootguard.cpp) ──
// Cách dùng: 2 lần boot power-on + giữ nút (lần 2 giữ >= OTA_BTN_HOLD_MS rồi
// nhả trong OTA_BTN_RELEASE_MS) -> nối WiFi debug, tải DEFAULT_OTA_URL
// (phải là file .uf2, không cần Content-Length), nạp rồi khởi động lại.
#define OTA_BTN_KEY               "ota_btn"
#define OTA_BTN_HOLD_MS           5000    // giữ nút liên tục ít nhất 5s...
#define OTA_BTN_RELEASE_MS        5000    // ...rồi nhả trong 5s kế tiếp -> vào OTA
#define DEFAULT_OTA_URL           "http://192.168.137.1:8090/firmware.uf2"
#define OTA_WIFI_TIMEOUT_MS       60000   // chờ kết nối WiFi tối đa 60s
#define OTA_TASK_STACK            8192    // stack cho task otaUpload
#define OTA_CHUNK_SIZE            1400    // buffer đọc HTTP khi tải firmware


// ── FreeRTOS task config ──
#define TASK_NETWORK_STACK       4096
#define TASK_NETWORK_PRIO        3
#define TASK_SENSOR_STACK        2024
#define TASK_SENSOR_PRIO         4
#define TASK_NTPCLIENT_STACK     512

#endif // CONFIG_H
