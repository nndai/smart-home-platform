#include "ToggleLog.h"
#include "LogStorageManager.h"

ToggleLog::ToggleLog(TimeManager& tm) : LogBase(tm, DIR) {}
bool ToggleLog::begin() { _mounted = true; return true; }

void ToggleLog::log(Source src, bool on) {
    if (!_mounted) return;
    char payload[12];
    snprintf(payload, sizeof(payload), "%u|%u", uint8_t(src), on ? 1 : 0);
    LogStorageManager::instance().enqueue(logv2::Toggle, payload,
        _time.isTimeSynced() ? _time.getEpoch() : 0);
}
