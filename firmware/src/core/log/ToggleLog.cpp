#include "ToggleLog.h"

ToggleLog::ToggleLog(TimeManager& tm) : LogBase(tm, DIR) {}

bool ToggleLog::begin() {
    _mounted = true;
    return true;
}

String ToggleLog::_path() {
    if (_time.isTimeSynced())
        return String(DIR) + _time.dateStr() + ".log";
    String p = _latestFile(DIR);
    if (p.length() > 0) return p;
    return String(DIR) + "01-01-1970.log";
}

void ToggleLog::log(Source src, bool on) {
    if (!_mounted) return;
    _rotate();

    String path = _path();
    File f = LITTLEFS.open(path, "a");
    if (!f) return;

    String ts;
    if (_time.isTimeSynced()){
        ts = _time.ts();
        ts.remove(ts.length() - 4);
    }
    else {
        ts = "?" + String(millis() / 1000) + "?";
    }
    
    f.print(ts);
    f.print("|");
    f.print((uint8_t)src);
    f.print("|");
    f.print(on ? "1" : "0");
    f.print("\n");
    f.close();
}

void ToggleLog::_rotate() {
    _rotateBySize(DIR, MAX_FOLDER);
}
