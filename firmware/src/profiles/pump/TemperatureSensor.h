#pragma once

#include <Arduino.h>

class TemperatureSensor {
public:
    TemperatureSensor();
    void begin(int adcPin);
    float readCelsius();
    float readFahrenheit();
    int readRaw();

private:
    int _adcPin;
    bool _initialized;
    float _resistanceToCelsius(float resistance);
};
