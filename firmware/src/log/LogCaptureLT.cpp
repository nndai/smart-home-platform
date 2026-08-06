#include <Arduino.h>
#include <cstring>
#include <cstdlib>
#include "LogManager.h"

extern "C" void __real_putchar_p(char c, unsigned long port);

#define PREINIT_MAX 64
#define LINE_MAX 256

struct LogEntry {
    char* line;
    bool writtenToFile;
};

static char s_lineBuf[LINE_MAX];
static int  s_linePos = 0;

static LogEntry s_buffer[PREINIT_MAX];
static int      s_bufferCount = 0;
static bool     s_fileFlushed = false;
static bool     s_callbackFlushed = false;
static bool     s_ready = false;

static void flushLine() {
    s_lineBuf[s_linePos] = '\0';
    if (s_linePos > 0) {
        if (!s_callbackFlushed) {
            bool added = false;
            if (s_bufferCount < PREINIT_MAX) {
                s_buffer[s_bufferCount].line = (char*)malloc(s_linePos + 1);
                if (s_buffer[s_bufferCount].line) {
                    memcpy(s_buffer[s_bufferCount].line, s_lineBuf, s_linePos + 1);
                    s_buffer[s_bufferCount].writtenToFile = false;
                    s_bufferCount++;
                    added = true;
                }
            }
            if (s_fileFlushed) {
                extern LogManager logManager;
                logManager.writeFile(s_lineBuf);
                if (added) {
                    s_buffer[s_bufferCount - 1].writtenToFile = true;
                }
            }
        } else {
            extern LogManager logManager;
            logManager.ingest(s_lineBuf);
        }
    }
    s_linePos = 0;
}

extern "C" void __wrap_putchar_p(char c, unsigned long port) {
    __real_putchar_p(c, port);
    if (c == '\n') {
        flushLine();
    } else if (c != '\r' && s_linePos < (LINE_MAX - 1)) {
        s_lineBuf[s_linePos++] = c;
    }
}

extern "C" void logCaptureFlushFile(LogManager* lm) {
    if (!lm) return;
    for (int i = 0; i < s_bufferCount; i++) {
        if (!s_buffer[i].writtenToFile) {
            lm->writeFile(s_buffer[i].line);
        }
    }
    s_fileFlushed = true;
}

extern "C" void logCaptureFlushCallback(LogManager::LogCallback cb) {
    if (!cb) return;
    for (int i = 0; i < s_bufferCount; i++) {
        if (s_buffer[i].line) {
            cb(String(s_buffer[i].line));
            free(s_buffer[i].line);
            s_buffer[i].line = nullptr;
        }
        delay(100);
        yield();
    }
    s_bufferCount = 0;
    s_callbackFlushed = true;
    s_ready = true;
}

extern "C" bool logCaptureIsDone() {
    return s_callbackFlushed;
}
