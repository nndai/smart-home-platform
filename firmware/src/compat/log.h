#pragma once

#include <Arduino.h>

// ── Trên LibreTiny: dùng lt_logger gốc (gating theo -DLT_DEBUG_<module>) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <lt_logger.h>

// ── MCU khác: mirror cùng giao diện LT_I/LT_E/LT_IM/LT_EM ──
#else

// Level constants (giá trị mirror lt_config.h của LibreTiny)
#define LT_LEVEL_TRACE 0
#define LT_LEVEL_DEBUG 1
#define LT_LEVEL_INFO 2
#define LT_LEVEL_WARN 3
#define LT_LEVEL_ERROR 4
#define LT_LEVEL_FATAL 5

// Mặc định bật mọi module (tương đương -DLT_DEBUG_<module>=1 trên LT)
#ifndef LT_DEBUG_BTN
#define LT_DEBUG_BTN 1
#endif
#ifndef LT_DEBUG_CFG
#define LT_DEBUG_CFG 1
#endif
#ifndef LT_DEBUG_CMD
#define LT_DEBUG_CMD 1
#endif
#ifndef LT_DEBUG_NET
#define LT_DEBUG_NET 1
#endif
#ifndef LT_DEBUG_OTA
#define LT_DEBUG_OTA 1
#endif
#ifndef LT_DEBUG_PUMP
#define LT_DEBUG_PUMP 1
#endif
#ifndef LT_DEBUG_SYS
#define LT_DEBUG_SYS 1
#endif
#ifndef LT_DEBUG_WS
#define LT_DEBUG_WS 1
#endif

// ESP8266: os_printf_plus with PSTR puts format strings directly into Flash (IROM),
// saving ~4KB-5KB of static DRAM.
// MCU khác (ESP32...): Serial.printf.
#if defined(ARDUINO_ARCH_ESP8266)
#include "compat/log_capture.h"

#define LT_I(fmt, ...)                               \
	do {                                            \
		logPrintf_P("[I] ", PSTR(fmt), ##__VA_ARGS__); \
	} while (0)

#define LT_E(fmt, ...)                               \
	do {                                            \
		logPrintf_P("[E] ", PSTR(fmt), ##__VA_ARGS__); \
	} while (0)

#define LT_IM(module, fmt, ...)                      \
	do {                                            \
		if (LT_DEBUG_##module) {                    \
			logPrintf_P("[I][" #module "] ", PSTR(fmt), ##__VA_ARGS__); \
		}                                           \
	} while (0)

#define LT_EM(module, fmt, ...)                      \
	do {                                            \
		if (LT_DEBUG_##module) {                    \
			logPrintf_P("[E][" #module "] ", PSTR(fmt), ##__VA_ARGS__); \
		}                                           \
	} while (0)

#else
#define LT_PRINTF(...) Serial.printf(__VA_ARGS__)

#define LT_I(...)                                   \
	do {                                            \
		LT_PRINTF("[I] ");                          \
		LT_PRINTF(__VA_ARGS__);                     \
		LT_PRINTF("\n");                            \
	} while (0)

#define LT_E(...)                                   \
	do {                                            \
		LT_PRINTF("[E] ");                          \
		LT_PRINTF(__VA_ARGS__);                     \
		LT_PRINTF("\n");                            \
	} while (0)

#define LT_IM(module, ...)                          \
	do {                                            \
		if (LT_DEBUG_##module) {                    \
			LT_PRINTF("[I][" #module "] ");         \
			LT_PRINTF(__VA_ARGS__);                 \
			LT_PRINTF("\n");                        \
		}                                           \
	} while (0)

#define LT_EM(module, ...)                          \
	do {                                            \
		if (LT_DEBUG_##module) {                    \
			LT_PRINTF("[E][" #module "] ");         \
			LT_PRINTF(__VA_ARGS__);                 \
			LT_PRINTF("\n");                        \
		}                                           \
	} while (0)

#endif

#endif
