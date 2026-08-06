#pragma once

#include "core/log/LogManager.h"

// ── LibreTiny: log capture từ printf (--wrap=putchar_p, xem chip/log_capture.cpp) ──
#if defined(LT_ARD_HAS_SERIAL)
extern "C" void logCaptureFlushFile(LogManager* lm);
extern "C" void logCaptureFlushCallback(LogManager::LogCallback cb);
extern "C" bool logCaptureIsDone();

// ── MCU khác: không có ──
#else
inline void logCaptureFlushFile(LogManager*) {}
inline void logCaptureFlushCallback(LogManager::LogCallback) {}
inline bool logCaptureIsDone() { return true; }
#endif
