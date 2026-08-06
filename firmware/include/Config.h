#ifndef CONFIG_H
#define CONFIG_H

#include "generic-ln882h.h"

// ── Version ──
#define FIRMWARE_VERSION   "1.0.0"
#define DEVICE_NAME        "RemotePump"
#ifdef MCU
#define CHIP_MODEL       MCU
#else
#define CHIP_MODEL       "LN882HK"
#endif
// ── LN882H Pin Definitions ──
#define PIN_BL0937_CF      PIN_PB04  // PB04  - BL0937 CF  (power pulse, interrupt)
#define PIN_BL0937_CF1     PIN_PB05  // PB05  - BL0937 CF1 (current/voltage pulse, interrupt)
#define PIN_BL0937_SEL     PIN_PB06  // PB06  - BL0937 SEL (current/voltage select)
#define PIN_NTC_ADC        PIN_PA04  // PA04  - NTC 10k thermistor (ADC-capable pin)
#define PIN_RELAY          PIN_PB03  // PB03  - Relay control
#define PIN_TRIAC_GATE     PIN_PA08  // PA08  - TRIAC gate control
#define PIN_LED            PIN_PA06  // PA06  - Status LED (active LOW)
#define PIN_BUTTON         PIN_PA07  // PA07  - Push button (active LOW, pull-up)

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

// ── BL0937 Defaults ──
#define CURRENT_MIN_INTERVAL_MS   500     // interval tối thiểu giữa 2 lần tính dòng điện

// ── Default Current Thresholds (mA) ──
#define DEFAULT_THRESH_OFF          100     // <100mA  = not running
#define DEFAULT_THRESH_NO_WATER     2000    // <2000mA = no water (dry run)
#define DEFAULT_THRESH_RUNNING      5000    // <5000mA = normal running
#define DEFAULT_THRESH_OVERLOAD     20000   // >20000mA = overload/short

// ── Default Timeouts (ms) ──
#define DEFAULT_NO_WATER_TIMEOUT    7000   // 7s dry run => auto off
#define DEFAULT_OVERLOAD_TIMEOUT    1000    // 1s overload => auto off

// ── Default Pump Mode ──
#define DEFAULT_PUMP_MODE          true
#define PUMP_CRITICAL_PERCENT      125     // dòng >= 125% ngưỡng running -> critical

// ── NTC Thermistor (10k + 10k series) ──
#define NTC_SERIES_RESISTOR     10000.0f    // 10k series resistor
#define NTC_NOMINAL_RES         10000.0f    // 10k at 25°C
#define NTC_NOMINAL_TEMP        25.0f       // 25°C
#define NTC_B_VALUE             3950.0f     // Beta coefficient
#define NTC_ADC_MAX             4095.0f     // 12-bit ADC
#define NTC_VREF                3.3f        // Reference voltage

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


// ── OTA khẩn cấp bằng tay (OtaBootGuard, xem src/OtaBootGuard.cpp) ──
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
#define TASK_SENSOR_STACK        512
#define TASK_SENSOR_PRIO         4
#define TASK_BUTTON_STACK        1024
#define TASK_BUTTON_PRIO         1
#define TASK_LED_STACK           512
#define TASK_LED_PRIO            1
#define TASK_NTPCLIENT_STACK     512

#endif // CONFIG_H
