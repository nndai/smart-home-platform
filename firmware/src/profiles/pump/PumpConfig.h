#pragma once

#include "core/ConfigManager.h"

// ── Config riêng của profile PUMP ──
struct PumpConfig : public DeviceConfig {
    // ── Pump protection thresholds (mA) ──
    uint16_t threshOff = DEFAULT_THRESH_OFF;
    uint16_t threshNoWater = DEFAULT_THRESH_NO_WATER;
    uint16_t threshRunning = DEFAULT_THRESH_RUNNING;
    uint16_t threshOverload = DEFAULT_THRESH_OVERLOAD;

    // ── Pump protection timeouts (ms) ──
    uint16_t dryTimeout = DEFAULT_NO_WATER_TIMEOUT;
    uint16_t overloadTimeout = DEFAULT_OVERLOAD_TIMEOUT;

    // ── Device mode ──
    bool pumpMode = DEFAULT_PUMP_MODE;

    // ── Relay startup mode (OFF=0 / ON=1 / LAST=2) — riêng profile relay ──
    RelayStartMode relayStartMode = RelayStartMode::OFF;

    // ── BL0937 calibration coefficients (NAN = use HW defaults) ──
    double cCal = NAN;   // current coefficient
    double vCal = NAN;   // voltage coefficient
    double pCal = NAN;   // power coefficient

    // Chỉ append field mới ở cuối struct, không chèn giữa.
};
