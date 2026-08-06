#pragma once
#include <Arduino.h>
#include <time.h>

class TimeManager {
public:
    TimeManager();
    void setTime(unsigned long epoch);
    bool isTimeSynced() const;
    unsigned long getEpoch() const;
    String ts() const;
    String dateStr() const;
private:
    bool _timeSynced = false;
    unsigned long _epoch = 0;
    unsigned long _epochMillis = 0;
};
