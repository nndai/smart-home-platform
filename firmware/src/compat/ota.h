#pragma once

// ── OTA Update header đa nền tảng ──
#if defined(ARDUINO_ARCH_ESP8266)
#include <Updater.h>

#ifndef UPDATE_SIZE_UNKNOWN
#define UPDATE_SIZE_UNKNOWN ((size_t)-1)
#endif

class Esp8266UpdateWrapper {
public:
    bool begin(size_t size = UPDATE_SIZE_UNKNOWN, int command = U_FLASH, int ledPin = -1, uint8_t ledOn = LOW, const char *label = nullptr) {
        (void)label;
        return Update.begin(size, command, ledPin, ledOn);
    }
    size_t write(uint8_t *data, size_t len) { return Update.write(data, len); }
    size_t writeStream(Stream &stream) { return Update.writeStream(stream); }
    bool end(bool evenIfRemaining = false) { return Update.end(evenIfRemaining); }
    bool isFinished() const { return Update.isFinished(); }
    bool hasError() const { return Update.hasError(); }
    void printError(Stream &out) { Update.printError(out); }
    const char* errorString() const {
        static String s_err;
        s_err = Update.getErrorString();
        return s_err.c_str();
    }
    void abort() { Update.end(true); }
    bool canRollBack() const { return false; }
    bool rollBack() { return false; }
    void clearError() { Update.clearError(); }
};

static Esp8266UpdateWrapper g_esp8266UpdateWrapper;
#define Update g_esp8266UpdateWrapper

#else
#include <Update.h>
#endif
