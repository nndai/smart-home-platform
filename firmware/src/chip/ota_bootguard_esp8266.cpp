/*
 * Emergency OTA Boot Guard for ESP8266.
 *
 * Runs as the VERY FIRST user code, before every other C++ constructor, thanks to
 * GCC's __attribute__((init_priority(101))) applied to a global object.
 *
 * How it hijacks the boot process:
 *   1. The ESP8266 core initializes the SDK, Wi-Fi, and heap in user_init().
 *   2. The core then calls do_global_ctors() which iterates backwards through
 *      the .ctors array.
 *   3. Because 101 is the highest priority, our constructor is placed at the
 *      BOTTOM of the .ctors array, meaning it gets executed FIRST by the
 *      backwards while-loop in do_global_ctors().
 *   4. If the OTA pattern is confirmed, the constructor calls ets_run() to
 *      start the SDK event loop indefinitely. This prevents the constructor
 *      from ever returning, which completely blocks the rest of the 
 *      do_global_ctors() loop from executing user/library constructors.
 *
 * Use: power-cycle twice with the button held. Boot #1 arms the guard
 * (state PREPARING). Boot #2 confirms the hold/release pattern and enters
 * OTA mode: the guard connects to the debug WiFi, downloads the firmware
 * image with the real Arduino Update library and reboots — before any
 * other constructor (e.g. a crashing one) can run.
 *
 * Why the guard can use yield()/delay()/Update here:
 *   The OTA flow runs INSIDE the core's own loop continuation g_pcont, driven
 *   by a guard task registered at LOOP_TASK_PRIORITY (1) — the same priority
 *   esp_yield()/esp_schedule() post to. Because we hijacked the boot, the
 *   core's own loop_task cannot be used. Our resumer task plays its role:
 *   every yield()/delay() inside the guard resumes via cont_run().
 *   With a running continuation, yield() is safe and the unmodified
 *   Arduino Update class (begin/write/end, flash-mode patch, verify, eboot
 *   staging) works as-is.
 */
#if defined(ARDUINO_ARCH_ESP8266)

#include <Arduino.h>

#include <EEPROM.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266WiFi.h>
#include <Updater.h>
#include <Esp.h>

#include <cont.h>
#include <eagle_soc.h>
#include <osapi.h>
#include <os_type.h>
#include <user_interface.h>

#include <Config.h>

#ifndef OTA_BTN_PIN
#error "OTA_BTN_PIN must be defined"
#endif
#ifndef OTA_BTN_ACTIVE_LOW //true if button is active low, false if active high
#error "OTA_BTN_ACTIVE_LOW must be defined"
#endif

#ifndef OTA_LED_PIN
#error "OTA_LED_PIN must be defined"
#endif
#ifndef OTA_LED_ACTIVE_LOW //true if LED is active low, false if active high
#error "OTA_LED_ACTIVE_LOW must be defined"
#endif

// Set UART0 baud. The APB clock is ALWAYS 80MHz inside preinit(): the ROM
// boots at 80MHz and the Arduino core only switches to 160MHz later, in
// loop_wrapper(). system_get_cpu_freq() must NOT be used here — it calls a
// ROM function (0x40002f0c) whose result is unreliable this early (observed:
// prints at a garbage baud). Same math as Serial.begin (uart.cpp:606:
// USD = ESP8266_CLOCK / baud).
//
// The TX FIFO is drained first: changing UART_CLKDIV while bytes are still
// queued (or a byte is mid-shift) garbles the tail of the last print
// (observed: "...bypassin" + garbage).
static void uartSetBaud(uint32_t baud) {
    while (((*(volatile uint32_t *)0x6000001C >> 16) & 0xFF) != 0) { }
    for (volatile uint32_t i = 0; i < 3000; i++) { } // last byte still shifting
    uart_div_modify(0, 80000000UL / baud);
}

// esp_yield()/esp_schedule() and the core's loop_task all run at priority 1
// (LOOP_TASK_PRIORITY). Our resumer task must register at the same priority
// so yield()/delay() inside the guard continuation get resumed.
#define OTA_RESUME_TASK_PRIO 1
#define OTA_RESUME_QUEUE_LEN 8

// State persisted across reboots in the EEPROM sector (4 bytes).
enum OtaBtnState : uint8_t {
    OTA_BTN_STATE_IDLE = 0,
    OTA_BTN_STATE_PREPARING = 1,
};

static const uint8_t OTA_STATE_MAGIC[3] = {'O', 'T', 'A'};

// ── Button / LED  ──────────────────────────────────────────────────────────
static bool otaButtonPressed() {
    return digitalRead(OTA_BTN_PIN) == (OTA_BTN_ACTIVE_LOW ? LOW : HIGH);
}

static void otaLedSet(bool on) {
    digitalWrite(OTA_LED_PIN, on ? (OTA_LED_ACTIVE_LOW ? LOW : HIGH) : (OTA_LED_ACTIVE_LOW ? HIGH : LOW));
}

// ── EEPROM state ───────────────────────────────────────────────────────────
static bool otaStateRead(OtaBtnState &state) {
    EEPROMClass eeprom;
    eeprom.begin(4);
    if (eeprom.read(0) == OTA_STATE_MAGIC[0] &&
        eeprom.read(1) == OTA_STATE_MAGIC[1] &&
        eeprom.read(2) == OTA_STATE_MAGIC[2]) {
        state = static_cast<OtaBtnState>(eeprom.read(3));
        return true;
    }
    state = OTA_BTN_STATE_IDLE;
    return false;
}

static void otaStateWrite(OtaBtnState state) {
    EEPROMClass eeprom;
    eeprom.begin(4);
    eeprom.write(0, OTA_STATE_MAGIC[0]);
    eeprom.write(1, OTA_STATE_MAGIC[1]);
    eeprom.write(2, OTA_STATE_MAGIC[2]);
    eeprom.write(3, static_cast<uint8_t>(state));
    eeprom.commit();
    eeprom.end();
}

static bool otaConfirmPattern() {
    uint32_t heldAt = millis();
    otaLedSet(true);
    while (millis() - heldAt < OTA_BTN_HOLD_MS) {
        if (!otaButtonPressed()) {
            otaLedSet(false);
            return false;
        }
        system_soft_wdt_feed();
        ets_delay_us(10000);
    }

    heldAt = millis();
    bool ledOn = true;
    while (millis() - heldAt < OTA_BTN_RELEASE_MS) {
        system_soft_wdt_feed();
        ets_delay_us(100000);
        ledOn = !ledOn;
        otaLedSet(ledOn);
        if (!otaButtonPressed()) {
            otaLedSet(false);
            return true;
        }
    }
    otaLedSet(false);
    return false;
}

// ── OTA flow ───────────────────────────────────────────────────────────────
// Runs INSIDE the core's loop continuation g_pcont: the resumer task calls
// cont_run(&g_pcont, otaGuardMain) and every yield()/delay() the OTA makes
// resumes through it (the core's own loop_task is never registered because
// user_init() never completes after the ets_run() hijack).

static void otaGuardMain();

static os_event_t s_resumeQueue[OTA_RESUME_QUEUE_LEN];
static os_timer_t s_ota_delay_timer;

static void otaResumerTask(os_event_t *event) {
    (void)event;
    cont_run(g_pcont, otaGuardMain);
}

static void otaSafeDelay(uint32_t ms) {
    system_soft_wdt_feed();
    os_timer_disarm(&s_ota_delay_timer);
    os_timer_setfn(&s_ota_delay_timer, [](void*) {
        system_os_post(OTA_RESUME_TASK_PRIO, 0, 0);
    }, NULL);
    os_timer_arm(&s_ota_delay_timer, ms, 0);
    cont_suspend(g_pcont); // Yields completely to SDK
}

static void otaGuardAbort() {
    os_printf("[guard] OTA aborted, rebooting\n");
    otaStateWrite(OTA_BTN_STATE_IDLE);
    system_restart();
    while (true) {
        otaSafeDelay(100);
    }
}

static void otaGuardMain() {
    os_printf("[guard] OTA mode\n");

    os_printf("[guard] Initializing WiFi (SDK)\n");
    
    wifi_fpm_do_wakeup();
    wifi_fpm_close();
    wifi_set_opmode_current(STATION_MODE);

    struct station_config conf;
    memset(&conf, 0, sizeof(conf));
    strncpy(reinterpret_cast<char*>(conf.ssid), DEFAULT_DEBUG_SSID, sizeof(conf.ssid));
    strncpy(reinterpret_cast<char*>(conf.password), DEFAULT_DEBUG_PASSWORD, sizeof(conf.password));
    wifi_station_set_config(&conf);

    wifi_station_connect();

    os_printf("[guard] Connecting to WiFi\n");
    uint32_t started = millis();
    uint8_t last_status = 255;
    
    while (millis() - started < OTA_WIFI_TIMEOUT_MS) {
        uint8_t status = wifi_station_get_connect_status();
        if (status != last_status) {
            os_printf("[guard] Wi-Fi status: %d\n", status);
            last_status = status;
        }
        if (status == STATION_GOT_IP) {
            break;
        }
        otaSafeDelay(100);
    }
    
    if (wifi_station_get_connect_status() != STATION_GOT_IP) {
        os_printf("[guard] WiFi connect failed (final status: %d)\n", wifi_station_get_connect_status());
        otaGuardAbort();
        return;
    }
    os_printf("[guard] WiFi connected\n");

    WiFiClient wifiClient;
    HTTPClient http;
    if (!http.begin(wifiClient, DEFAULT_OTA_URL_ESP8266)) {
        os_printf("[guard] HTTP begin failed\n");
        http.end();
        otaGuardAbort();
        return;
    }
    http.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
    int status = http.GET();
    if (status != HTTP_CODE_OK) {
        os_printf("[guard] HTTP GET %d\n", status);
        http.end();
        otaGuardAbort();
        return;
    }

    int imageSize = http.getSize();
    if (imageSize <= 0) {
        os_printf("[guard] no Content-Length\n");
        http.end();
        otaGuardAbort();
        return;
    }
    os_printf("[guard] image size %d bytes\n", imageSize);

    UpdaterClass update;
    if (!update.begin(imageSize)) {
        os_printf("[guard] Update.begin failed (errno 0x%02X)\n", static_cast<unsigned>(update.getError()));
        http.end();
        otaGuardAbort();
        return;
    }

    WiFiClient *stream = http.getStreamPtr();
    uint8_t buffer[OTA_CHUNK_SIZE] __attribute__((aligned(4)));
    size_t written = 0;
    bool ledOn = false;
    while (written < static_cast<size_t>(imageSize) && http.connected()) {
        size_t avail = stream->available();
        if (avail == 0) {
            otaSafeDelay(10);
            continue;
        }
        size_t n = stream->readBytes(buffer, std::min(
            static_cast<unsigned long long>(avail),
            static_cast<unsigned long long>(sizeof(buffer))
        ));
        if (n == 0) {
            continue;
        }
        size_t w = update.write(buffer, n);
        if (w != n) {
            os_printf("[guard] Update.write failed (errno 0x%02X)\n", static_cast<unsigned>(update.getError()));
            http.end();
            otaGuardAbort();
            return;
        }
        written += n;
        ledOn = !ledOn;
        otaLedSet(ledOn);
    }
    http.end();
    otaLedSet(false);

    if (written != static_cast<size_t>(imageSize)) {
        os_printf("[guard] short read: %u/%d bytes\n", written, imageSize);
        otaGuardAbort();
        return;
    }
    if (!update.end(true)) {
        os_printf("[guard] Update.end failed (errno 0x%02X)\n", static_cast<unsigned>(update.getError()));
        otaGuardAbort();
        return;
    }

    os_printf("[guard] flashed %d bytes, rebooting\n", imageSize);
    otaStateWrite(OTA_BTN_STATE_IDLE);
    otaLedSet(true);
    system_restart();
    while (true) {
        otaSafeDelay(100);
    }
}
// ── Boot guard ─────────────────────────────────────────────────────────────
// Overrides the core's weak preinit() hook. preinit() runs after the heap,
// millis() and the SDK event system are initialized, but before ALL C++
// constructors (core library statics AND any future user statics) and
// before the core disables WiFi at boot — true crash-proof first-run.

class OtaBootGuard {
public:
    OtaBootGuard() {
        // This constructor runs at priority 101, which is BEFORE any user constructors
        // (priority 65535) but AFTER the SDK and Wi-Fi are fully initialized!
        
        // initPins() (core_esp8266_wiring_digital.cpp:248) ran right before this:
        // it set GPIO1 to INPUT (killing UART0 TX) and zeroed the SDK
        // os_print flag. Restore TX exactly like Serial.begin() does
        // (uart.cpp:670: pinMode(1, FUNCTION_0)), then set the monitor baud.
        pinMode(1, FUNCTION_0);
        uartSetBaud(74880);
        system_set_os_print(1);
        
        os_printf("\n\n[guard] OtaBootGuard constructor running...\n");

        // chỉ xử lý khi boot do cấp nguồn hoặc reset pin cứng; SOFTWARE/WATCHDOG -> bỏ qua
        uint32_t rstReason = system_get_rst_info()->reason;
        if (rstReason != REASON_EXT_SYS_RST && rstReason != REASON_DEFAULT_RST) {
            return;
        }
        
        pinMode(OTA_BTN_PIN, OTA_BTN_ACTIVE_LOW ? INPUT_PULLUP : INPUT);
        pinMode(OTA_LED_PIN, OUTPUT);
        otaLedSet(false);
        
        // State Machine: PREPARING -> OTA / IDLE
        OtaBtnState state = OTA_BTN_STATE_IDLE;
        otaStateRead(state);

        os_printf("[guard] button %s, state %d\n",
                  otaButtonPressed() ? "pressed" : "released",
                  static_cast<int>(state));

        if (!otaButtonPressed()) {
            // power-on, không nhấn nút -> reset trạng thái về ban đầu nếu khác
            if (state != OTA_BTN_STATE_IDLE) {
                otaStateWrite(OTA_BTN_STATE_IDLE);
                os_printf("[guard] state reset to IDLE\n");
            }
            return;
        }

        if (state != OTA_BTN_STATE_PREPARING) {
            // power-on + nút nhấn, chưa ở trạng thái chuẩn bị -> đánh dấu và boot tiếp
            otaStateWrite(OTA_BTN_STATE_PREPARING);
            os_printf("[guard] armed (PREPARING), booting normally\n");
            otaLedSet(true);
            delay(300); // LED xác nhận đã lưu trạng thái chuẩn bị
            otaLedSet(false);
            return;
        }

        // power-on + nút nhấn + đang PREPARING -> xác nhận pattern giữ/nhả
        os_printf("[guard] hold >=%u ms, release within %u ms to enter OTA\n", OTA_BTN_HOLD_MS, OTA_BTN_RELEASE_MS);
        if (otaConfirmPattern()) {
            os_printf("[guard] pattern OK, entering OTA mode\n");
            
            // Register our priority 1 task and POST it so it runs immediately
            if (system_os_task(otaResumerTask, OTA_RESUME_TASK_PRIO, s_resumeQueue, OTA_RESUME_QUEUE_LEN) &&
                system_os_post(OTA_RESUME_TASK_PRIO, 0, 0)) {
                // Hijack the main loop to prevent any other constructors from running!
                ets_run(); 
            }
            
            otaGuardAbort();
            return;
        }
        os_printf("[guard] pattern failed, booting normally (state stays PREPARING)\n");
    }
};

// Priority 101 ensures this runs FIRST among all global constructors
OtaBootGuard __attribute__((init_priority(101))) g_otaBootGuard;

#endif // ARDUINO_ARCH_ESP8266