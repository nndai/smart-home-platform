#include "SysLog.h"
#include "LogStorageManager.h"
#include <algorithm>
#include <string.h>

SysLog::SysLog(TimeManager& tm) : LogBase(tm, DIR) {}
bool SysLog::begin() { _mounted = true; return true; }
void SysLog::ingest(const char* line) {
    if (!line) return;
    if (_cb) _cb(String(line));
    writeFile(line);
}
void SysLog::writeFile(const char* line) {
    if (!line || !_mounted || !_fileEnabled) return;
    uint8_t level = LT_LEVEL_INFO;
    const size_t length = strnlen(line, logv2::MAX_PAYLOAD + 1);
    const char c = length >= 3 && line[0] == '[' && line[2] == ']' ? line[1] : line[0];
    if (c == 'T') level = LT_LEVEL_TRACE;
    else if (c == 'D') level = LT_LEVEL_DEBUG;
    else if (c == 'W') level = LT_LEVEL_WARN;
    else if (c == 'E') level = LT_LEVEL_ERROR;
    else if (c == 'F') level = LT_LEVEL_FATAL;
    if (level < _fileLevel) return;
    char bounded[logv2::MAX_PAYLOAD + 1];
    size_t n = std::min(length, logv2::MAX_PAYLOAD);
    // Avoid cutting a UTF-8 code point at the message size limit.
    if (length > n) while (n && (uint8_t(line[n]) & 0xc0) == 0x80) --n;
    memcpy(bounded, line, n); bounded[n] = 0;
    LogStorageManager::instance().enqueue(logv2::Sys, bounded,
        _time.isTimeSynced() ? _time.getEpoch() : 0);
}
void SysLog::setEnabled(bool en) { _fileEnabled = en; }
void SysLog::setLevel(uint8_t lv) { _fileLevel = lv; }
void SysLog::setCallback(LogCallback cb) { _cb = cb; }
void SysLog::migrateNosync(unsigned long) { /* Legacy files remain read-only. */ }
