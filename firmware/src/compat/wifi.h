#pragma once

// ── WiFi header đa nền tảng ──
#if defined(ARDUINO_ARCH_ESP8266)
#include <ESP8266WiFi.h>
#else
#include <WiFi.h>
#endif
