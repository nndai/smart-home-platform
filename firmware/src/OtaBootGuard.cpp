/*
 * OTA Boot Guard — cơ chế OTA khẩn cấp bằng tay.
 *
 * Runs BEFORE all other constructors thanks to the constructor priority:
 *   __attribute__((constructor(101)))  ->  .init_array.00101
 * The linker script sorts ".init_array.*" before ".init_array", so this
 * function is the first thing executed by __libc_init_array():
 *
 *   lt_main -> lt_init_family() -> __libc_init_array() -> [OTA BOOT GUARD]
 *                                        -> ...all other ctors...
 *                                        -> fal_init() -> main() -> setup()
 *
 * lt_init_family() has already initialized: sys clock, interrupts, the DWT
 * runtime counter, serial/log, NVDS, KV and sysparam. FreeRTOS heap regions
 * are registered, so pvPortMalloc()/xSemaphoreCreateBinary() already work.
 *
 * Cách dùng (2 lần boot power-on):
 *   1. Giữ nút + cấp nguồn -> guard thấy power-on + nút nhấn, trạng thái KV
 *      chưa phải PREPARING -> ghi PREPARING, boot bình thường.
 *   2. Tắt nguồn, giữ nút + cấp nguồn lần nữa -> power-on + nút nhấn + trạng
 *      thái đang PREPARING -> chờ giữ nút >= OTA_BTN_HOLD_MS rồi nhả trong
 *      OTA_BTN_RELEASE_MS -> vào chế độ OTA: nối WiFi debug, tải
 *      DEFAULT_OTA_URL, nạp và khởi động lại. Nếu pattern không khớp thì boot
 *      bình thường (state vẫn PREPARING để thử lại).
 *
 * Boot khác (SOFTWARE/WATCHDOG) -> guard không làm gì. Power-on mà không nhấn
 * nút -> reset trạng thái về IDLE.
 *
 * Không dùng WDT trong guard (cơ chế cứu hộ thủ công: nếu treo thì cắt nguồn
 * và làm lại). LED (PIN_LED, active LOW) báo trạng thái:
 *   - boot 1 - đã lưu PREPARING        : LED sáng 300ms
 *   - boot 2 - đang giữ nút (>=5s)      : LED sáng liên tục
 *   - boot 2 - chờ nhả nút (trong 5s)   : LED nháy nhanh 100ms
 *   - chế độ OTA - chờ kết nối WiFi     : LED sáng liên tục (tối đa 60s)
 *   - chế độ OTA - đang tải/ghi firmware: LED đảo mỗi 50ms
 *   - flash OK                          : LED sáng 1s rồi reboot
 *   - thất bại                          : LED tắt rồi reboot
 *
 * Khi vào chế độ OTA, guard "cướp" quá trình boot: placement-new lên global
 * WiFiClass, tạo task OTA tự chứa rồi khởi động scheduler. Các ctor còn lại,
 * setup() và toàn bộ task người dùng không bao giờ chạy.
 *
 * Task OTA chỉ phụ thuộc API C và object trên stack/heap — không có global
 * constructor của người dùng, không dùng FAL (Update ghi flash thô qua
 * lt_ota), nên chạy trước mọi ctor khác là an toàn.
 */
#include <Arduino.h>
#include <FreeRTOS.h>
#include <task.h>
#include <Config.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <Update.h>
#include <sdk_private.h>




// ── Cấu hình OTA khẩn cấp (defines: Config.h) ──────────────────────────────

enum OtaBtnState : uint8_t {
    OTA_BTN_STATE_IDLE = 0,      // chưa bắt đầu chuỗi khẩn cấp
    OTA_BTN_STATE_PREPARING = 1, // đã cấp nguồn lần 1 với nút nhấn
};

// ── Trạng thái KV: 1 byte, key "ota_btn" ───────────────────────────────────

static OtaBtnState otaStateRead() {
    uint8_t v = (uint8_t)OTA_BTN_STATE_IDLE;
    size_t len = 0;
    if (ln_kv_get(OTA_BTN_KEY, &v, 1, &len) != KV_ERR_NONE) return OTA_BTN_STATE_IDLE;
    if (len != 1) return OTA_BTN_STATE_IDLE;
    return (OtaBtnState)v;
}

static void otaStateWrite(OtaBtnState v) {
    ln_kv_set(OTA_BTN_KEY, &v, 1);
}

// ── Đồng hồ pre-scheduler (DWT cycle counter, lt_init_family đã bật) ───────

static uint32_t otaNowMs() {
    static uint32_t lastCycles = 0;
    static uint32_t accMs = 0;
    uint32_t now = ln_runtime_get_curr_cycles();
    if (lastCycles != 0) {
        accMs += ln_runtime_get_delta_ticks(lastCycles, now);
    }
    lastCycles = now;
    return accMs;
}

// ── Nút boot: busy-wait sampling (chưa có scheduler nên không dùng
//    vTaskDelay/delay()). Trả true nếu nút đang nhấn (active LOW theo
//    BUTTON_ACTIVE_LOW). ─────────────────────────────────────────────────────

static bool otaButtonInitDone = false;
static uint32_t otaBtnBase;
static gpio_pin_t otaBtnPin;

static bool otaButtonPressed() {
    if (!otaButtonInitDone) {
        const uint32_t pin = (uint32_t)PIN_BUTTON;
        otaBtnBase = (pin >> 4) ? GPIOB_BASE : GPIOA_BASE;
        otaBtnPin = (gpio_pin_t)(1u << (pin & 0xF));

        gpio_init_t_def gpio;
        memset(&gpio, 0, sizeof(gpio));
        gpio.pin = otaBtnPin;
        gpio.pull = GPIO_PULL_UP;
        gpio.speed = GPIO_NORMAL_SPEED;
        gpio.mode = GPIO_MODE_DIGITAL;
        gpio.dir = GPIO_INPUT;
        hal_gpio_init(otaBtnBase, &gpio);
        ln_block_delayms(5);
        otaButtonInitDone = true;
    }
    return hal_gpio_pin_input_read(otaBtnBase, otaBtnPin) == (BUTTON_ACTIVE_LOW ? LOW : HIGH);
}

// ── LED: hal_gpio trực tiếp (chạy trước mọi ctor) — PIN_LED active LOW ─────

static bool otaLedInitDone = false;
static uint32_t otaLedBase;
static gpio_pin_t otaLedPin;

static void otaLedSet(bool on) {
    if (!otaLedInitDone) {
        const uint32_t pin = (uint32_t)PIN_LED;
        otaLedBase = (pin >> 4) ? GPIOB_BASE : GPIOA_BASE;
        otaLedPin = (gpio_pin_t)(1u << (pin & 0xF));

        gpio_init_t_def gpio{};
        gpio.pin = otaLedPin;
        gpio.pull = GPIO_PULL_NONE;
        gpio.speed = GPIO_NORMAL_SPEED;
        gpio.mode = GPIO_MODE_DIGITAL;
        gpio.dir = GPIO_OUTPUT;
        hal_gpio_init(otaLedBase, &gpio);
        otaLedInitDone = true;
    }
    if (on == !LED_ACTIVE_LOW) hal_gpio_pin_set(otaLedBase, otaLedPin);
    else hal_gpio_pin_reset(otaLedBase, otaLedPin);
}

// ── Pattern xác nhận: giữ nút >= OTA_BTN_HOLD_MS rồi nhả trong
//    OTA_BTN_RELEASE_MS kế tiếp. LED: sáng khi đang giữ, nháy nhanh khi
//    chờ nhả. ─────────────────────────────────────────────────────────────

static bool otaConfirmPattern() {
    uint32_t t0 = otaNowMs();
    otaLedSet(true); // LED sáng liên tục: đang đếm thời gian giữ
    while (otaNowMs() - t0 < OTA_BTN_HOLD_MS) {
        if (!otaButtonPressed()) { // nhả sớm -> không vào OTA
            otaLedSet(false);
            return false;
        }
        ln_block_delayms(10);
    }
    // giữ đủ -> LED nháy nhanh: yêu cầu nhả nút trong OTA_BTN_RELEASE_MS
    uint32_t tHeld = otaNowMs();
    bool ledOn = true;
    while (otaNowMs() - tHeld < OTA_BTN_RELEASE_MS) {
        ln_block_delayms(100);
        ledOn = !ledOn;
        otaLedSet(ledOn);
        if (!otaButtonPressed()) { // nhả trong cửa sổ -> vào OTA
            otaLedSet(false);
            return true;
        }
    }
    otaLedSet(false);
    return false; // vẫn giữ quá lâu -> không vào OTA
}

// ── Task OTA (chạy sau khi scheduler khởi động) ────────────────────────────
// Nối WiFi debug với DEFAULT_DEBUG_* trong Config.h, tải firmware.uf2 từ
// DEFAULT_OTA_URL, nạp rồi khởi động lại.
// Chỉ thử 1 lần: thành công hay thất bại đều reboot — boot sau có cause
// SOFTWARE nên guard bỏ qua; state vẫn PREPARING để thử lại nếu cần.

static bool otaDownloadAndFlash(const char* url) {
    HTTPClient http;
    http.begin(url);
    http.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
    http.addHeader("User-Agent", "LN882H-OTA/1.0");

    int code = http.GET();
    if (code != HTTP_CODE_OK) {
        LT_EM(OTA, "Manual OTA: HTTP error %d", code);
        http.end();
        return false;
    }

    int total = http.getSize();
    // UPDATE_SIZE_UNKNOWN -> Update.begin gọi lt_ota_begin size=0: không cần
    // Content-Length, UF2 tự mang kích thước trong mỗi block
    if (total <= 0) total = 1024 * 1024; // giả sử 1MB nếu không có Content-Length
    
    if (!Update.begin(total, U_FLASH)) {
        LT_EM(OTA, "Manual OTA: Update.begin failed: %s", Update.errorString());
        http.end();
        return false;
    }

    WiFiClient* stream = http.getStreamPtr();
    uint8_t buf[OTA_CHUNK_SIZE];
    size_t written = 0;
    bool ledOn = false;
    uint32_t lastLed = millis();

    while (http.connected() && written < (size_t)total) {
        if (millis() - lastLed >= 50) {
            ledOn = !ledOn;
            otaLedSet(ledOn);
            lastLed = millis();
        }

        size_t avail = stream->available();
        if (avail > 0) {
            size_t n = stream->readBytes(buf, std::min(avail, sizeof(buf)));
            if (n > 0) written += Update.write(buf, n);
        } else {
            vTaskDelay(pdMS_TO_TICKS(10));
        }
    }
    otaLedSet(false);

    bool ok = Update.end(true);
    http.end();
    if (!ok) {
        LT_EM(OTA, "Manual OTA: update failed: %s", Update.errorString());
        return false;
    }
    LT_IM(OTA, "Manual OTA: flash OK (%u bytes)", written);
    return true;
}

static void otaUploadTask(void* pv) {
    (void)pv;
    LT_IM(OTA, "Manual OTA: entering OTA mode");

    new (&WiFi) WiFiClass();
    WiFi.begin(DEFAULT_DEBUG_SSID, DEFAULT_DEBUG_PASSWORD);

    uint32_t t0 = millis();
    otaLedSet(true);
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < OTA_WIFI_TIMEOUT_MS) {
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    if (WiFi.status() != WL_CONNECTED) {
        LT_EM(OTA, "Manual OTA: WiFi connect failed");
        otaLedSet(false);
        goto finish;
    }

    vTaskDelay(pdMS_TO_TICKS(3000));
    {
        LT_IM(OTA, "Manual OTA: WiFi connected, downloading %s", DEFAULT_OTA_URL);
        bool ok = otaDownloadAndFlash(DEFAULT_OTA_URL);
        otaLedSet(ok); // flash OK -> LED sáng 1s rồi reboot; fail -> tắt rồi reboot
    }

finish:
    LT_IM(OTA, "Manual OTA: rebooting (thử lại: cắt nguồn, giữ nút + cấp nguồn 2 lần)");
    vTaskDelay(pdMS_TO_TICKS(1000));
    ln_chip_reboot();
    for (;;) {}
}

// ── Cướp quá trình boot: tạo task OTA và khởi động scheduler ───────────────

static void otaEnterMode() {
    xTaskCreate(otaUploadTask, "otaUpload", OTA_TASK_STACK, NULL, TASK_NETWORK_PRIO + 1, NULL);
    vTaskStartScheduler();
    for (;;) {} // never reached
}

// ── Boot guard ──────────────────────────────────────────────────────────────
// IntelliSense (MSVC-based parser) does not understand GCC's constructor
// priority — only the real GCC toolchain needs it. The build is unaffected.
#ifdef __INTELLISENSE__
#define OTA_GUARD_CTOR
#else
#define OTA_GUARD_CTOR __attribute__((constructor(101)))
#endif

OTA_GUARD_CTOR
void otaBootGuard() {
    // chỉ xử lý khi boot do cấp nguồn; SOFTWARE/WATCHDOG -> không làm gì
    if (ln_chip_get_reboot_cause() != CHIP_REBOOT_POWER_ON) return;

    OtaBtnState state = otaStateRead();

    if (!otaButtonPressed()) {
        // power-on, không nhấn nút -> reset trạng thái về ban đầu nếu khác
        if (state != OTA_BTN_STATE_IDLE) {
            otaStateWrite(OTA_BTN_STATE_IDLE);
            LT_IM(OTA, "Manual OTA: state reset to IDLE");
        }
        return;
    }

    if (state != OTA_BTN_STATE_PREPARING) {
        // power-on + nút nhấn, chưa ở trạng thái chuẩn bị -> đánh dấu và boot tiếp
        otaStateWrite(OTA_BTN_STATE_PREPARING);
        LT_IM(OTA, "Manual OTA: armed (PREPARING), booting normally");
        otaLedSet(true);
        ln_block_delayms(300); // LED xác nhận đã lưu trạng thái chuẩn bị
        otaLedSet(false);
        return;
    }

    // power-on + nút nhấn + đang PREPARING -> xác nhận pattern giữ/nhả
    LT_IM(OTA, "Manual OTA: giữ nút >=%u ms rồi nhả trong %u ms để vào OTA", OTA_BTN_HOLD_MS, OTA_BTN_RELEASE_MS);
    if (otaConfirmPattern()) {
        LT_IM(OTA, "Manual OTA: pattern OK, entering OTA mode");
        otaEnterMode();
        for (;;) {} // never reached
    }
    LT_IM(OTA, "Manual OTA: pattern failed, booting normally (state vẫn PREPARING)");
}
