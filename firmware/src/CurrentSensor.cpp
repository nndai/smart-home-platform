#include "CurrentSensor.h"
#include <Config.h>

volatile uint32_t CurrentSensor::s_cfPulses = 0;
volatile uint32_t CurrentSensor::s_cf1Pulses = 0;

CurrentSensor::CurrentSensor()
    : _cfPin(-1)
    , _cf1Pin(-1)
    , _selPin(-1)
    , _initialized(false)
    , _voltage(0)
    , _current(0)
    , _power(0)
    , _vCal(EMS_VOLTAGE_CAL)
    , _cCal(EMS_CURRENT_CAL)
    , _pCal(EMS_POWER_CAL)
    , _measVoltage(false)
    , _lastLoopMs(0)
    , _prevCf(0)
    , _prevCf1(0)
    , _totalHourWs(0)
    , _totalDayWs(0)
{
}

void CurrentSensor::begin(int cfPin, int cf1Pin, int selPin, uint8_t selStartLevel) {
    _cfPin = cfPin;
    _cf1Pin = cf1Pin;
    _selPin = selPin;

    pinMode(_cfPin, INPUT_PULLUP);
    pinMode(_cf1Pin, INPUT_PULLUP);
    pinMode(_selPin, OUTPUT);

    _measVoltage = (selStartLevel == HIGH);
    digitalWrite(_selPin, selStartLevel);

    attachInterrupt(_cfPin, _cfISR, RISING);
    attachInterrupt(_cf1Pin, _cf1ISR, RISING);

    s_cfPulses = 0;
    s_cf1Pulses = 0;
    _prevCf = 0;
    _prevCf1 = 0;
    _lastLoopMs = millis();

    _initialized = true;
}

void CurrentSensor::loop() {
    if (!_initialized) return;

    unsigned long now = millis();
    unsigned long elapsed = now - _lastLoopMs;
    if (elapsed < CURRENT_MIN_INTERVAL_MS) return;
    _lastLoopMs = now;

    uint32_t curCf = s_cfPulses;
    uint32_t curCf1 = s_cf1Pulses;
    uint32_t cf = curCf - _prevCf;
    uint32_t cf1 = curCf1 - _prevCf1;

    _prevCf = curCf;
    _prevCf1 = curCf1;

    float elapsedSec = elapsed / 1000.0f;

    if (_measVoltage) {
        _voltage = cf1 * _vCal / elapsedSec;
    } else {
        _current = cf1 * _cCal / elapsedSec;
    }

    double tmp = cf * _pCal;

    _power = tmp / elapsedSec;

    _totalHourWs += tmp;
    _totalDayWs += tmp;

    _measVoltage = !_measVoltage;
    digitalWrite(_selPin, _measVoltage ? HIGH : LOW);
}

BL0937SensorData CurrentSensor::readAll() {
    if (!_initialized) return {0, 0, 0, 0, 0, 0, 0};

    float voltage = _voltage;
    float current = _current;
    float power = _power;
    double dailyEnergy = _totalDayWs / 3600.0;
    double hourlyEnergy = _totalHourWs / 3600.0;
    float apparent = voltage * current;
    float pf = (apparent > 0) ? power / apparent : 0;

    return {current, voltage, power, apparent, pf, hourlyEnergy, dailyEnergy};
}

void CurrentSensor::calibrateCurrent(double expectedCurrent) {
    if (!_initialized) return;
    if (_current > 0) {
        double factor = expectedCurrent / _current;
        _cCal *= factor;
    }
}

void CurrentSensor::calibrateVoltage(unsigned int expectedVoltage) {
    if (!_initialized) return;
    if (_voltage > 0) {
        double factor = (double)expectedVoltage / _voltage;
        _vCal *= factor;
    }
}

void CurrentSensor::calibratePower(unsigned int expectedPower) {
    if (!_initialized) return;
    if (_power > 0) {
        double factor = (double)expectedPower / _power;
        _pCal *= factor;
    }
}

void CurrentSensor::resetCalibration() {
    if (!_initialized) return;
    _vCal = EMS_VOLTAGE_CAL;
    _cCal = EMS_CURRENT_CAL;
    _pCal = EMS_POWER_CAL;
}

void CurrentSensor::resetHourlyEnergy() {
    _totalHourWs = 0;
}

void CurrentSensor::resetDailyEnergy() {
    _totalDayWs = 0;
}

void CurrentSensor::_cfISR() {
    s_cfPulses++;
}

void CurrentSensor::_cf1ISR() {
    s_cf1Pulses++;
}
