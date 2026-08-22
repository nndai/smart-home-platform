// Log capture dùng chung cho mọi MCU.
//
// Bắt toàn bộ output đi qua printf/ets_printf (log LT_ + log SDK wifi khi
// os_print=1) theo từng ký tự, gom thành dòng:
//  - trước khi LITTLEFS init: lưu tạm tối đa PREINIT_MAX dòng (cấp phát động,
//    mỗi dòng ≤ PREINIT_LINE_MAX ký tự) — logCaptureFlushFile() sau khi
//    logManager.begin() sẽ ghi vào file rồi xóa cấp phát động;
//  - sau đó: đẩy thẳng vào LogManager qua ingest() — LogManager tự lo phần
//    còn lại: ghi file (SysLog::writeFile guard LITTLEFS + config bật) và
//    forward WS/MQTT (SysLog::ingest gọi callback nếu đã set).
//
// Nguồn ký tự theo chip:
//  - LibreTiny: --wrap=putchar_p (link time) → __wrap_putchar_p ở dưới;
//  - ESP8266:   hook putc1 qua ets_install_putc1 (logCaptureInit).
#include <Arduino.h>
#include <cstring>
#include <cstdlib>
#include "compat/log_capture.h"
#include "core/log/LogManager.h"

#define LINE_LOG_MAX 256
#define PREINIT_MAX 15
#define PREINIT_LINE_MAX 128

static char s_lineBuf[LINE_LOG_MAX];
static int  s_linePos = 0;

static char* s_preBuffer[PREINIT_MAX];
static int   s_preCount = 0;
static bool  s_fileFlushed = false;

void logCaptureChar(char c) {
    if (c == '\n') {
        s_lineBuf[s_linePos] = '\0';
        if (s_linePos > 0) {
            if (!s_fileFlushed) {
                // LITTLEFS chưa init: lưu tạm (giữ tối đa PREINIT_MAX dòng)
                if (s_preCount < PREINIT_MAX) {
                    int len = s_linePos < PREINIT_LINE_MAX ? s_linePos : PREINIT_LINE_MAX;
                    char* p = (char*)malloc(len + 1);
                    if (p) {
                        memcpy(p, s_lineBuf, len);
                        p[len] = '\0';
                        s_preBuffer[s_preCount++] = p;
                    }
                }
            } else {
                extern LogManager logManager;
                logManager.ingest(s_lineBuf);
            }
        }
        s_linePos = 0;
    } else if (c != '\r' && s_linePos < (LINE_LOG_MAX - 1)) {
        s_lineBuf[s_linePos++] = c;
    }
}

void logCaptureFlushFile(LogManager* lm) {
    if (s_fileFlushed) return;
    for (int i = 0; i < s_preCount; i++) {
        if (lm) lm->writeFile(s_preBuffer[i]);
        free(s_preBuffer[i]);
        s_preBuffer[i] = nullptr;
    }
    s_preCount = 0;
    s_fileFlushed = true;
}