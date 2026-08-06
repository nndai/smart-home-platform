#pragma once

// ── Filesystem đa nền tảng ──
// LibreTiny: LITTLEFS (global từ src/chip/lfs, xem platformio.ini -Isrc/chip/lfs).
// ESP32: LittleFS (có sẵn trong core) + types nằm trong namespace fs.
#if defined(LT_ARD_HAS_SERIAL)
#include <LittleFS.h>
#else
#include <LittleFS.h>
using namespace fs;
#define LITTLEFS LittleFS
#endif
