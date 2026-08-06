#pragma once

#include <Arduino.h>

// Default calibration values
#define EMS_VOLTAGE_CAL   0.121708
#define EMS_CURRENT_CAL   0.012219731
#define EMS_POWER_CAL     1.375000049

struct BL0937SensorData {
    float current;       // A
    float voltage;       // V
    float power;         // W
    float apparent;      // VA
    float pf;
    double hourlyEnergy; // Wh
    double dailyEnergy;  // Wh
};

class CurrentSensor {
public:
    CurrentSensor();
    void begin(int cfPin, int cf1Pin, int selPin, uint8_t selStartLevel = HIGH);
    void loop();

    void calibrateCurrent(double expectedCurrent);
    void calibrateVoltage(unsigned int expectedVoltage);
    void calibratePower(unsigned int expectedPower);
    void resetCalibration();

    float getCurrent() const { return _current; } // A
    float getActivePower() const { return _power; } // W
    float getVoltage() const {  return _voltage; } // V

    double getDailyEnergy() const { return _totalDayWs / 3600.0; }    // Wh
    double getHourlyEnergy() const { return _totalHourWs / 3600.0; } // Wh

    float getApparentPower() const { return _voltage * _current; }  // VA
    float getPowerFactor() const {
        float apparent = _voltage * _current;
        return (apparent > 0) ? _power / apparent : 0;
    }
    void resetHourlyEnergy();
    void resetDailyEnergy();

    double getCurrentMultiplier() const { return _cCal; }
    double getVoltageMultiplier() const { return _vCal; }
    double getPowerMultiplier() const { return _pCal; }
    void setCurrentMultiplier(double v) { _cCal = v; }
    void setVoltageMultiplier(double v) { _vCal = v; }
    void setPowerMultiplier(double p) { _pCal = p; }

    BL0937SensorData readAll();

private:
    // GPIO
    int _cfPin;
    int _cf1Pin;
    int _selPin;
    bool _initialized;

    // Interrupt counters
    static volatile uint32_t s_cfPulses;
    static volatile uint32_t s_cf1Pulses;

    // Measured values
    float _voltage; // V
    float _current; // A
    float _power;   // W

    // Calibration
    double _vCal;
    double _cCal;
    double _pCal;

    // true = last period measured voltage, false = measured current
    bool _measVoltage;
    unsigned long _lastLoopMs;

    // Delta tracking
    uint32_t _prevCf;
    uint32_t _prevCf1;

    // Energy tracking
    double _totalHourWs;
    double _totalDayWs;

    static void _cfISR();
    static void _cf1ISR();
};
