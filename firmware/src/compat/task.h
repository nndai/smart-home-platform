#pragma once

// ── FreeRTOS include đa nền tảng ──
#if defined(LT_ARD_HAS_SERIAL)
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
#elif defined(ARDUINO_ARCH_ESP32)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#else
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
#endif
