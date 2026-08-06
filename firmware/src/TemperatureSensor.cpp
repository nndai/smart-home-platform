#include "TemperatureSensor.h"
#include <Config.h>

TemperatureSensor::TemperatureSensor()
    : _adcPin(-1)
    , _initialized(false)
{
}

void TemperatureSensor::begin(int adcPin) {
    _adcPin = adcPin;
    pinMode(adcPin, INPUT);
    analogReadResolution(12);
    _initialized = true;
}

float TemperatureSensor::readCelsius() {
    if (!_initialized) return -273.0f;

    int raw = readRaw();
    if (raw <= 0) return -273.0f;

    float voltage = (raw / NTC_ADC_MAX) * NTC_VREF;
    if (voltage <= 0) return -273.0f;

    float resistance = NTC_SERIES_RESISTOR * (voltage / (NTC_VREF - voltage));
    return _resistanceToCelsius(resistance);
}

float TemperatureSensor::readFahrenheit() {
    return readCelsius() * 9.0f / 5.0f + 32.0f;
}

int TemperatureSensor::readRaw() {
    if (!_initialized) return 0;
    int sum = 0;
    for (int i = 0; i < 16; i++) {
        sum += analogRead(_adcPin);
    }
    return sum >> 4;
}

float TemperatureSensor::_resistanceToCelsius(float resistance) {
    if (resistance <= 0) return -273.0f;

    float steinhart;
    steinhart = resistance / NTC_NOMINAL_RES;
    steinhart = log(steinhart);
    steinhart /= NTC_B_VALUE;
    steinhart += 1.0f / (NTC_NOMINAL_TEMP + 273.15f);
    steinhart = 1.0f / steinhart;

    return steinhart - 273.15f;
}
