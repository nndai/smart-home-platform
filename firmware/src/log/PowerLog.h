#pragma once
#include "LogBase.h"

class PowerLog : public LogBase {
public:
    PowerLog(TimeManager& tm);
    bool begin() override;

    //void log(unsigned long power);
    void logHourly(uint8_t hour, uint32_t energyWh, time_t intervalEpoch = 0);

    static constexpr const char* DIR = "/logs/power/";

private:
    void _rotate();
    String _path();

    static constexpr size_t MAX_FOLDER = 200 * 1024;
};
