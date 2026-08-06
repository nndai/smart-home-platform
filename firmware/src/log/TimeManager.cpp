#include "TimeManager.h"
#include <Config.h>

TimeManager::TimeManager() {}

void TimeManager::setTime(unsigned long epoch) {
    if (epoch > EPOCH_VALID_MIN) {
        _epochMillis = millis();
        _epoch = epoch;
        _timeSynced = true;
    } else {
        _timeSynced = false;
    }
}

bool TimeManager::isTimeSynced() const { return _timeSynced; }

unsigned long TimeManager::getEpoch() const {
    unsigned long elapsed = (millis() - _epochMillis) / 1000;
    if (!_timeSynced) return (millis() / 1000);
    return _epoch + elapsed;
}

String TimeManager::ts() const {
    if (_timeSynced) {
        unsigned long now = millis();
        time_t raw = getEpoch();
        unsigned int ms = (now - _epochMillis) % 1000;
        struct tm ti;
        gmtime_r(&raw, &ti);
        char buf[16];
        snprintf(buf, sizeof(buf), "%02u:%02u:%02u.%03u",
                 ti.tm_hour, ti.tm_min, ti.tm_sec, ms);
        return String(buf);
    }
    unsigned long ms = millis();
    unsigned long t = ms / 1000;
    char buf[16];
    snprintf(buf, sizeof(buf), "%02lu:%02lu:%02lu.%03lu",
             t / 3600, (t / 60) % 60, t % 60, ms % 1000);
    return String(buf);
}

String TimeManager::dateStr() const {
    if (_timeSynced) {
        time_t raw = getEpoch();
        struct tm ti;
        gmtime_r(&raw, &ti);
        char buf[12];
        snprintf(buf, sizeof(buf), "%02d-%02d-%04d",
                 ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900);
        return String(buf);
    }
    return "nosync";
}
