#pragma once

// ── Pins + tham số riêng của profile PUMP (LN882H) ──
// Được include bởi include/Config.h khi build với -DPROFILE_PUMP.
// PIN_PAxx/PIN_PBxx đến từ board header của LibreTiny (chỉ có trên LT).
#include "generic-ln882h.h"

// ── LN882H Pin Definitions ──
#define PIN_BL0937_CF      PIN_PB04  // PB04  - BL0937 CF  (power pulse, interrupt)
#define PIN_BL0937_CF1     PIN_PB05  // PB05  - BL0937 CF1 (current/voltage pulse, interrupt)
#define PIN_BL0937_SEL     PIN_PB06  // PB06  - BL0937 SEL (current/voltage select)
#define PIN_NTC_ADC        PIN_PA04  // PA04  - NTC 10k thermistor (ADC-capable pin)
#define PIN_RELAY          PIN_PB03  // PB03  - Relay control
#define PIN_TRIAC_GATE     PIN_PA08  // PA08  - TRIAC gate control
#define PIN_LED            PIN_PA06  // PA06  - Status LED (active LOW)
#define PIN_BUTTON         PIN_PA07  // PA07  - Push button (active LOW, pull-up)

// ── BL0937 Defaults ──
#define CURRENT_MIN_INTERVAL_MS   500     // interval tối thiểu giữa 2 lần tính dòng điện

// ── Default Current Thresholds (mA) ──
#define DEFAULT_THRESH_OFF          100     // <100mA  = not running
#define DEFAULT_THRESH_NO_WATER     2000    // <2000mA = no water (dry run)
#define DEFAULT_THRESH_RUNNING      5000    // <5000mA = normal running
#define DEFAULT_THRESH_OVERLOAD     20000   // >20000mA = overload/short

// ── Default Timeouts (ms) ──
#define DEFAULT_NO_WATER_TIMEOUT    7000   // 7s dry run => auto off
#define DEFAULT_OVERLOAD_TIMEOUT    1000    // 1s overload => auto off

// ── Default Pump Mode ──
#define DEFAULT_PUMP_MODE          true
#define PUMP_CRITICAL_PERCENT      125     // dòng >= 125% ngưỡng running -> critical

// ── NTC Thermistor (10k + 10k series) ──
#define NTC_SERIES_RESISTOR     10000.0f    // 10k series resistor
#define NTC_NOMINAL_RES         10000.0f    // 10k at 25°C
#define NTC_NOMINAL_TEMP        25.0f       // 25°C
#define NTC_B_VALUE             3950.0f     // Beta coefficient
#define NTC_ADC_MAX             4095.0f     // 12-bit ADC
#define NTC_VREF                3.3f        // Reference voltage
