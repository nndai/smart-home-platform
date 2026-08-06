# CẤU TRÚC SOURCE CODE HỆ SINH THÁI — 1 Source, N Thiết bị, N Chip

> Mô tả cách tổ chức source code dùng chung cho nhiều loại thiết bị (bơm, đèn, quạt...) trên nhiều MCU (LN882H/LibreTiny, ESP32, ESP8266) mà **không nhân bản code**.

## 1. Tổng quan repo (monorepo)

```
RemotePumpLN882H/
├── app/                          # Android app (Kotlin/Compose)
├── firmware/                     # 1 source tree cho MỌI thiết bị
│   ├── include/  src/
│   ├── boards/                   # định nghĩa board (nếu cần custom)
│   ├── profiles/                 # N folder "device model" — chỉ chứa PHẦN KHÁC NHAU
│   │   ├── pump/                 #   + BL0937, NTC, relay, auto-dry-run/overload
│   │   ├── switch/               #   + relay đơn
│   │   └── fan/                  #   + triac/PWM, speed
│   ├── lib/                      # vendor libs (1 bản duy nhất, đã portable)
│   └── platformio.ini            # env: pump_ln882h / switch_esp32 / fan_esp32 ...
├── tools/                        # provisioning script, bridge, dumper
└── docs/
```

**Nguyên tắc**: 1 source tree dùng chung ~90% (DeviceIdentity, MQTT+TLS, envelope seq/hmac, Pairing Portal, OTA, capability registry). Không tách "n folder device model" thành n project — sẽ nhân bản toàn bộ phần dùng chung.

## 2. Mental model — 1 source, N binary

Cùng 1 file `.cpp`, PlatformIO compile **N lần với N toolchain khác nhau** — khác nhau ở include path (framework nào) và define (profile nào):

```
src/core/*.cpp ──┬─→ [gcc LibreTiny  ] ── -DPROFILE_PUMP   ──→ firmware_pump_ln882h.bin
                 ├─→ [gcc arduino-esp32 ] ── -DPROFILE_SWITCH ──→ firmware_switch_esp32.bin
                 └─→ [gcc arduino-esp8266] ── -DPROFILE_FAN    ──→ firmware_fan_esp8266.bin
```

Ví dụ: `#include <Update.h>` — cả 3 framework đều có file này với **cùng API** (`begin/write/end/abort`). Mỗi env lấy bản hiện thực của riêng framework. → **Cứ viết API chuẩn, framework nào cũng có bản hiện thực của nó.**

## 3. Quy tắc 3 tầng (quan trọng nhất)

| Tầng | Chứa gì | Quy tắc |
|---|---|---|
| **core/** | MqttClient, CommandHandler, OTA, Pairing Portal, identity | Chỉ dùng Arduino-standard API (`WiFi.h`, `WiFiClientSecure`, `Update.h`, `LittleFS`, `ArduinoJson`, `PubSubClient`, `WebSockets`). **KHÔNG BAO GIỜ `#ifdef`** |
| **compat/** | `kv.h`, `tls.h`, `rt.h`, `pm.h`, `wifi_compat.h` | **Nơi chứa `#ifdef` duy nhất** — gói mọi khác biệt include/API |
| **chip/** | `anchor.cpp`, `softap.cpp`, `io.cpp` | Hiện thực per-chip (SDK riêng), mọi profile dùng chung |

Thêm **chip mới** = thêm impl trong `chip/` + có thể 1 file `compat/`. Core bất biến.

## 4. Bằng chứng — code hiện tại đã portable ~90%

| API trong code hiện tại | LN882H | ESP32 | ESP8266 |
|---|---|---|---|
| `Update.h` OTA (`OTAManager.cpp:2`, `OtaBootGuard.cpp:53`) | ✅ | ✅ | ✅ |
| `WiFi.scanNetworks/scanComplete/scanResult` | ✅ | ✅ | ✅ (chỉ nhận **2 tham số**) |
| `WiFiClientSecure` MQTT TLS 8883 | ✅ | ✅ | ✅ (cert dùng BearSSL) |
| `WiFi.softAP()/softAPIP()/softAPSSID()` | ✅ | ✅ | ✅ |
| `WiFi.setHostname/getMode/channel/RSSI` | ✅ | ✅ | ✅ (core 3.x) |
| `LittleFS`, `PubSubClient`, `WebSockets`, `OneButton`, `ArduinoJson` | ✅ | ✅ | ✅ |

## 5. Danh sách "5% thật sự khác nhau" + giải pháp

| # | Code hiện tại | Vấn đề trên ESP32/8266 | Giải pháp (`compat/` + `chip/`) |
|---|---|---|---|
| 1 | `ln_kv_get/set` (`ConfigManager.cpp:13,40`) | Không có — LN882H-specific | `kv.h`: LN882H→`ln_kv`; ESP32→`Preferences`; ESP8266→file LittleFS (~40 dòng) |
| 2 | `WiFi.scanNetworks(true,false,false,200)` | ESP8266 chỉ nhận 2 tham số | Đổi 2 tham số — chạy cả 3 |
| 3 | `WiFi.scanDelete()` | ESP8266 không có | Guard `#if !defined(ARDUINO_ARCH_ESP8266)` |
| 4 | `WiFi.onEvent(cb)` (`CommandHandler.cpp:923`) | ESP8266 không có | ESP8266: poll `scanComplete()` trong loop |
| 5 | `wifi_softap_start` raw SDK (`main.cpp:345`) + `ln_psk_calc` | Không có | `chip/softap.cpp`: LN882H→SDK path; khác→`WiFi.softAP()` (code đã có fallback sẵn) |
| 6 | TLS cert (`MqttClient`) | ESP8266 dùng BearSSL `X509List` | `compat/tls.h`: hàm `setTlsCACert(client, cert)` |
| 7 | `ln_pm_always_clk_disable_select` (`main.cpp:229`) | Không có | `compat/pm.h`: LN882H→SDK; khác→no-op |
| 8 | `ln_chip_reboot`, `ln_block_delayms`, `ln_runtime_*` (OtaBootGuard) | Không có | `compat/rt.h`: `ESP.restart()`, `delay()`, `millis()` |
| 9 | `hal_gpio_*` LED/nút (`OtaBootGuard.cpp:114`) | Không có | `chip/io.h`: LN882H→hal; khác→`digitalWrite/Read` |
| 10 | `WiFi.setSleep(true)` (`main.cpp:402`) | ESP8266: `setSleepMode` khác | Guard 1 dòng |

Tổng cộng: **~150 dòng compat** cho toàn bộ 3 chip.

## 6. Profile — thêm thiết bị mới không đụng main

**main.cpp: MỘT bản duy nhất, không bao giờ tạo file main khác.**

```cpp
// core/DeviceDriver.h — interface chuẩn, core gọi mù
class DeviceDriver {
public:
  virtual void begin(DeviceConfig& cfg) = 0;
  virtual void loop(uint32_t nowMs) = 0;
  virtual void handleCmd(const char* cmd, JsonDocument& payload, JsonDocument& resp) = 0;
  virtual void getStatus(JsonDocument& resp) = 0;
};

// profiles/registry.h — chỗ #ifdef duy nhất của toàn project
#if defined(PROFILE_PUMP)
  #include "pump/PumpDriver.h"
#elif defined(PROFILE_FAN)
  #include "fan/FanDriver.h"
#endif
inline DeviceDriver* createDriver() {
  #if defined(PROFILE_PUMP)  return new PumpDriver();
  #elif defined(PROFILE_FAN) return new FanDriver();
  #endif
}

// main.cpp — không đổi khi thêm device
#include "profiles/registry.h"
DeviceDriver* g_driver = createDriver();
void setup() { ...wifi/mqtt/pairing/ota...; g_driver->begin(cfg); }
void loop()  { ...tasks...; g_driver->loop(millis()); }
```

### Thêm thiết bị mới (ví dụ fan) — 3 bước

1. **Viết driver**: `profiles/fan/FanDriver.cpp` implement `DeviceDriver` (triac/PWM, `setSpeed`...)
2. **Khai báo registry**: thêm `#elif defined(PROFILE_FAN)` + `return new FanDriver()`
3. **Thêm env** trong `platformio.ini`:

```ini
[env:fan_esp32]
platform = espressif32
board = esp32dev
build_flags = -DPROFILE_FAN
```

## 7. Refactor từ code hiện tại — pump đi đâu

| Code trong `main.cpp` hiện tại | Về đâu |
|---|---|
| `currentSensor/relayController/pumpController` init (`main.cpp:32-37,198-220`) | `profiles/pump/PumpDriver.cpp` |
| `onPumpState` (`main.cpp:629`) | `profiles/pump/PumpDriver.cpp` |
| loop: đọc dòng → `pumpController.update` + energy log (`main.cpp:569-573`) | `profiles/pump/PumpDriver.cpp` |
| `reclaimRelayGpio` (`main.cpp:291`) | `chip/io.cpp` (giữ cho mọi profile) |
| setup WiFi / MQTT / pairing portal / OTA / tasks | **giữ nguyên trong main.cpp** |

## 8. Quy tắc tổng kết

- Thêm **thiết bị** (fan, đèn) → chỉ đụng `profiles/` + `platformio.ini`
- Thêm **chip** (ESP32, ESP8266) → chỉ đụng `chip/` + `compat/`
- `core/` + `main.cpp` → bất biến vĩnh viễn
- Core không bao giờ `#ifdef`; mọi khác biệt gói trong `compat/` (include/API) + `chip/` (SDK riêng) + `profiles/registry.h` (chọn driver)
- Env = (board × profile) — mỗi combo 1 env, source không đổi

## 9. Lưu ý ESP8266

- PubSubClient + MQTT TLS 8883 chạy bình thường (BearSSL), nhưng heap hẹp (~40KB) — phù hợp SWITCH/FAN đơn giản
- Thiết bị phức tạp (pairing portal WS + TLS + JSON cùng lúc) → ưu tiên ESP32-C2/C3
- Source không phải vấn đề — chỉ là chọn board phù hợp profile
