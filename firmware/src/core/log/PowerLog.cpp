#include "PowerLog.h"
#include "LogStorageManager.h"

PowerLog::PowerLog(TimeManager& tm) : LogBase(tm, DIR) {}
bool PowerLog::begin() { _mounted = true; return true; }

void PowerLog::logHourly(uint8_t hour, uint32_t energyWh, time_t intervalEpoch) {
    // Keep the existing policy: unsynchronized power intervals are not dated retroactively.
    if (!_mounted || !_time.isTimeSynced() || hour > 23) return;
    char payload[48];
    snprintf(payload, sizeof(payload), "%u|%lu|3600", hour, (unsigned long)energyWh);
    const uint32_t epoch = intervalEpoch > 0 ? uint32_t(intervalEpoch) : _time.getEpoch();
    LogStorageManager::instance().enqueue(logv2::Power, payload, epoch);
}
