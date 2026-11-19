#include "LogManager.h"
#include "compat/fs.h"
#include <Config.h>

LogManager::LogManager()
    : _sysLog(_time)
    , _toggleLog(_time)
    , _powerLog(_time)
{}

bool LogManager::begin() {
    if (!compat::mountStorage()) return false;

    for (const char* d : {SysLog::DIR, ToggleLog::DIR, PowerLog::DIR}) {
        if (!LITTLEFS.exists(d)) LITTLEFS.mkdir(d);
    }

    if (!LogStorageManager::instance().begin()) return false;

    _sysLog.begin();
    _toggleLog.begin();
    _powerLog.begin();
    _data.begin();

    return true;
}

void LogManager::maintenance() {
    static unsigned long lastMaint = 0;
    unsigned long now = millis();
    if (now - lastMaint < 3600000UL) return;
    lastMaint = now;
}

void LogManager::setTime(unsigned long epoch) {
    if (epoch > EPOCH_VALID_MIN) {
        bool firstSync = !_time.isTimeSynced();
        _time.setTime(epoch);
        if (firstSync) {
            for (uint8_t stream = 0; stream < logv2::StreamCount; ++stream) {
                LogStorageManager::instance().enqueue(logv2::Stream(stream), "", epoch, logv2::TimeAnchor);
            }
        }
    }
}

// ── System log delegates ──
void LogManager::ingest(const char* line) { _sysLog.ingest(line); }
void LogManager::writeFile(const char* line) { _sysLog.writeFile(line); }
void LogManager::setSysLogFileEnabled(bool en) { _sysLog.setEnabled(en); }
void LogManager::setSysLogFileLevel(uint8_t lv) { _sysLog.setLevel(lv); }
void LogManager::setLogCallback(LogCallback cb) { _sysLog.setCallback(cb); }
void LogManager::setMqttLogEnabled(bool en) { _mqttLogEnabled = en; }
bool LogManager::isMqttLogEnabled() const { return _mqttLogEnabled; }
size_t LogManager::getSysLogSize() { return LogStorageManager::instance().size(logv2::Sys); }
bool LogManager::readSysLog(String& out, size_t maxBytes) { return _sysLog.read(out, maxBytes); }
void LogManager::clearSysLog() { LogStorageManager::instance().clear(logv2::Sys); }

// ── Toggle log delegates ──
void LogManager::logToggle(ToggleSource src, bool on) { _toggleLog.log(src, on); }
size_t LogManager::getToggleLogSize() { return LogStorageManager::instance().size(logv2::Toggle); }
bool LogManager::readToggleLog(String& out, size_t maxBytes) { return _toggleLog.read(out, maxBytes); }
void LogManager::clearToggleLog() { LogStorageManager::instance().clear(logv2::Toggle); }

// ── Power log delegates ──
//void LogManager::logPower(unsigned long power) { _powerLog.log(power); }
void LogManager::logHourlyPower(uint8_t hour, uint32_t energyWh, time_t intervalEpoch) { _powerLog.logHourly(hour, energyWh, intervalEpoch); }
size_t LogManager::getPowerLogSize() { return LogStorageManager::instance().size(logv2::Power); }
bool LogManager::readPowerLog(String& out, size_t maxBytes) { return _powerLog.read(out, maxBytes); }
void LogManager::clearPowerLog() { LogStorageManager::instance().clear(logv2::Power); }

// ── Time delegates ──
bool LogManager::isTimeSynced() const { return _time.isTimeSynced(); }
unsigned long LogManager::getEpoch() const { return _time.getEpoch(); }
String LogManager::dateTimeStr() const { return _time.dateTimeStr(); }

// ── Data delegates ──
const LogManager::DataFile& LogManager::getData() const { return _data.getData(); }
void LogManager::addButtonCount(uint32_t c) { _data.addButtonCount(c); }
void LogManager::addToggleCount(uint32_t c) { _data.addToggleCount(c); }
void LogManager::addTotalPower(uint64_t p) { _data.addTotalPower(p); }
void LogManager::addPumpTime(uint32_t s) { _data.addPumpTime(s); }

// ── Filesystem ──
size_t LogManager::getTotalBytes() { return compat::fsTotalBytes(); }
size_t LogManager::getUsedBytes() { return compat::fsUsedBytes(); }
