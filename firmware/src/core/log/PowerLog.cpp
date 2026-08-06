#include "PowerLog.h"
#include <time.h>

PowerLog::PowerLog(TimeManager& tm) : LogBase(tm, DIR) {}

bool PowerLog::begin() {
    _mounted = true;
    return true;
}

String PowerLog::_path() {
    return String(DIR) + _time.dateStr() + ".log";
}

// void PowerLog::log(unsigned long power) {
//     if (!_mounted || !_time.isTimeSynced()) return;
//     _rotate();

//     String path = _path();
//     File f = LITTLEFS.open(path, "a");
//     if (!f) return;

//     String ts = _time.ts();
//     f.print(ts);
//     f.print("|");
//     f.print(power);
//     f.print("\n");
//     f.close();
// }

void PowerLog::logHourly(uint8_t hour, uint32_t energyWh, time_t intervalEpoch) {
    if (!_mounted || !_time.isTimeSynced()) return;
    _rotate();

    String path;
    if (intervalEpoch > 0) {
        struct tm ti;
        gmtime_r(&intervalEpoch, &ti);
        char buf[12];
        snprintf(buf, sizeof(buf), "%02d-%02d-%04d",
                 ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900);
        path = String(DIR) + buf + ".log";
    } else {
        path = _path();
    }

    File f = LITTLEFS.open(path, "a");
    if (!f) return;
    f.print(hour);
    f.print("|");
    f.print(energyWh);
    f.print("\n");
    f.close();
}

void PowerLog::_rotate() {
    _rotateBySize(DIR, MAX_FOLDER);
}
