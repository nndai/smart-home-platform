#include "compat/log_capture.h"
#include <Arduino.h>

#if defined(ARDUINO_ARCH_ESP8266)

#include <ets_sys.h>
#include <esp8266_peri.h>
#include <pgmspace.h>
#include <cstdarg>
#include <cstdio>

static inline bool txFifoFull(int uart_nr) {
    return ((USS(uart_nr) >> USTXC) & 0xff) >= 0x7f;
}

static void IRAM_ATTR capturePutc1(char c) {
    // Giữ nguyên output serial (tương đương uart0_write_char của core)
    while (txFifoFull(0)) esp_yield();
    USF(0) = (uint8_t)c;
    logCaptureChar(c);
}

void logCaptureInit() {
    ets_install_putc1(capturePutc1);
}

void logPrintf_P(const char* prefix, PGM_P fmt, ...) {
    char buf[256];
    int prefixLen = 0;
    if (prefix) {
        while (prefix[prefixLen] && prefixLen < 32) {
            buf[prefixLen] = prefix[prefixLen];
            prefixLen++;
        }
    }
    va_list args;
    va_start(args, fmt);
    int bodyLen = vsnprintf_P(buf + prefixLen, sizeof(buf) - prefixLen - 2, fmt, args);
    va_end(args);
    if (bodyLen < 0) return;
    int totalLen = prefixLen + bodyLen;
    if (totalLen >= (int)sizeof(buf) - 2) totalLen = sizeof(buf) - 2;
    buf[totalLen++] = '\n';
    buf[totalLen] = '\0';

    for (int i = 0; i < totalLen; i++) {
        capturePutc1(buf[i]);
    }
}

#elif defined(LT_ARD_HAS_SERIAL)

extern "C" void __real_putchar_p(char c, unsigned long port);
extern "C" void __wrap_putchar_p(char c, unsigned long port) {
    __real_putchar_p(c, port);
    logCaptureChar(c);
}

void logCaptureInit() {
    // LibreTiny: --wrap=putchar_p đã hoạt động từ link time, không cần gì.
}

#else

// Other MCUs (ESP32...): no serial-capture hook yet — serial output goes
// straight to UART, log capture stays a no-op.
void logCaptureInit() {
}

#endif
