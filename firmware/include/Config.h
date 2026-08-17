#ifndef CONFIG_H
#define CONFIG_H

// ── Pins + tham số riêng theo profile (build_flags: -DPROFILE_PUMP / -DPROFILE_SWITCH) ──
#if defined(PROFILE_PUMP)
#include "profiles/pump/config.h"
#elif defined(PROFILE_SWITCH)
#include "profiles/switch/config.h"
#elif defined(PROFILE_REMOTE_SWITCH)
#include "profiles/remote_switch/config.h"
#else
#error "Please define PROFILE_PUMP, PROFILE_SWITCH, or PROFILE_REMOTE_SWITCH in build_flags"
#endif

// ── Network ──
#define WEBSOCKET_PORT      82

// ── SoftAP (chế độ AP_WS / cấu hình) ──
#define DEFAULT_AP_PASSWORD     "123456789"

// ── File Paths (LittleFS) ──
#define PATH_CONFIG_FILE    "/config.json"
#define PATH_LOG_DIR        "/logs/"
#define PATH_LOG_FILE       "/logs/log.txt"

// ── Default WiFi DEBUG ──
#define DEFAULT_DEBUG_SSID      "DESKTOP-P5540"
#define DEFAULT_DEBUG_PASSWORD  "aaaaaaaa"
#define DEFAULT_DEBUG_IP        {192, 168, 137, 111}
#define DEFAULT_DEBUG_GATEWAY   {192, 168, 137, 1}
#define DEFAULT_DEBUG_NETMASK   {255, 255, 255, 0}

// ── System / RTOS ──
#define WDT_TIMEOUT_MS            15000   // watchdog timeout
#define WDT_FEED_INTERVAL_MS      2000    // task wdtFeed feed mỗi 2s

// heap dưới mức này -> restart
#if defined(ARDUINO_ARCH_ESP8266)
    #define HEAP_CRITICAL_BYTES   1024   
#else
    #define HEAP_CRITICAL_BYTES   4096
#endif

#define STREAM_DURATION_MS        120000  // thời lượng stream status/sysinfo (WS/MQTT)
#define EPOCH_VALID_MIN           1700000000  // epoch >= mức này mới coi là đã đồng bộ giờ

// ── Button ──
#define BUTTON_ACTIVE_LOW         true    // nút nhấn xuống mức LOW (pull-up)
#define BUTTON_LONG_PRESS_MS      5000    // giữ 5s để mở chuỗi thao tác; giữ thêm 5s -> bước kế
#define BUTTON_CONFIRM_TIMEOUT_MS 3000    // nhả nút trong 3s để xác nhận bước đã chọn
#define BUTTON_DEBOUNCE_MS        50

// ── LED ──
#define LED_ACTIVE_LOW             true    // LED sáng ở mức LOW (active low)

// ── MQTT ──
#define DEFAULT_MQTT_PORT          1883
#define DEFAULT_MQTT_TOPIC         "pump"
#define MQTT_SOCKET_TIMEOUT_SEC    7
#define MQTT_RECONNECT_INTERVAL_MS 5000  // khoảng cách giữa 2 lần thử kết nối lại

#if defined(ARDUINO_ARCH_ESP8266)
#define MQTT_BUFFER_SIZE           3072
#else
#define MQTT_BUFFER_SIZE           5000
#endif

// TZ: UTC+7 (Việt Nam)
# define TZ_OFFSET_SEC (7 * 3600)


// ── OTA khẩn cấp bằng tay (OtaBootGuard, xem src/chip/ota_bootguard.cpp) ──
// Cách dùng: 2 lần boot power-on + giữ nút (lần 2 giữ >= OTA_BTN_HOLD_MS rồi
// nhả trong OTA_BTN_RELEASE_MS) -> nối WiFi debug, tải DEFAULT_OTA_URL
// (phải là file .uf2, không cần Content-Length), nạp rồi khởi động lại.

#define OTA_BTN_KEY               "ota_btn" // key lưu trạng thái nút nhấn trong ln_kv(LN882H)
#define OTA_BTN_HOLD_MS           5000    // giữ nút liên tục ít nhất 5s...
#define OTA_BTN_RELEASE_MS        5000    // ...rồi nhả trong 5s kế tiếp -> vào OTA
#define DEFAULT_OTA_URL_LN882H    "http://192.168.137.1:8090/firmware.uf2"
#define DEFAULT_OTA_URL_ESP8266   "http://192.168.137.1:8090/firmware.bin"
#define OTA_WIFI_TIMEOUT_MS       60000   // chờ kết nối WiFi tối đa 60s
#define OTA_CHUNK_SIZE            1400    // buffer đọc HTTP khi tải firmware


// ── FreeRTOS task config (Stack & Priority) ──
#if defined(ARDUINO_ARCH_ESP8266)
// ESP8266 (NonOS Shim): usStackDepth tính bằng BYTE
#define TASK_NETWORK_STACK       4096   // 4KB
#define TASK_SENSOR_STACK        3072   // 4KB (đủ cho LittleFS + JSON)
#define TASK_NTP_STACK           1024   // 1KB
#define TASK_WDT_STACK           512    // 512B
#define TASK_STREAM_STACK        3072   // 3KB
#define TASK_LOGWRITER_STACK     1024   // 1KB
#define TASK_OTA_STACK           8192   // 8KB
#elif defined(ARDUINO_ARCH_ESP32)
// ESP32 (FreeRTOS chuẩn): usStackDepth tính bằng WORD (4 Bytes)
#define TASK_NETWORK_STACK       2048   // 8192 Bytes
#define TASK_SENSOR_STACK        1024   // 4096 Bytes
#define TASK_NTP_STACK           512    // 2048 Bytes
#define TASK_WDT_STACK           256    // 1024 Bytes
#define TASK_STREAM_STACK        1024   // 4096 Bytes
#define TASK_LOGWRITER_STACK     512    // 2048 Bytes
#define TASK_OTA_STACK           2048   // 8192 Bytes
#else
// LN882H / LibreTiny (FreeRTOS chuẩn): usStackDepth tính bằng WORD (4 Bytes)
#define TASK_NETWORK_STACK       2048   // 8192 Bytes
#define TASK_SENSOR_STACK        2024   // 8096 Bytes
#define TASK_NTP_STACK           512    // 2048 Bytes
#define TASK_WDT_STACK           256    // 1024 Bytes
#define TASK_STREAM_STACK        1024   // 4096 Bytes
#define TASK_LOGWRITER_STACK     512    // 2048 Bytes
#define TASK_OTA_STACK           2048   // 8192 Bytes
#endif


// Priorities
#define TASK_WDT_PRIO            1
#define TASK_LOGWRITER_PRIO      1
#define TASK_STREAM_PRIO         2
#define TASK_NETWORK_PRIO        3
#define TASK_NTP_PRIO            3
#define TASK_SENSOR_PRIO         4
#define TASK_OTA_PRIO            4

#endif // CONFIG_H
