#include "LogManager.h"
#include <LittleFS.h>
#include <Config.h>

LogManager::LogManager()
    : _sysLog(_time)
    , _toggleLog(_time)
    , _powerLog(_time)
{}

bool LogManager::begin() {
    if (!LITTLEFS.begin()) return false;

    for (const char* d : {SysLog::DIR, ToggleLog::DIR, PowerLog::DIR}) {
        if (!LITTLEFS.exists(d)) LITTLEFS.mkdir(d);
    }

    if (LITTLEFS.exists("/logs/nosync.log")) {
        LITTLEFS.remove("/logs/nosync.log");
    }
    String oldPowerNosync = String(PowerLog::DIR) + "nosync.log";
    if (LITTLEFS.exists(oldPowerNosync)) {
        LITTLEFS.remove(oldPowerNosync);
    }

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
            _sysLog.migrateNosync(epoch);
        }
    }
}

// ── System log delegates ──
void LogManager::ingest(const char* line) { _sysLog.ingest(line); }
void LogManager::writeFile(const char* line) { _sysLog.writeFile(line); }
void LogManager::setSysLogFileEnabled(bool en) { _sysLog.setEnabled(en); }
void LogManager::setSysLogFileLevel(uint8_t lv) { _sysLog.setLevel(lv); }
void LogManager::setLogCallback(LogCallback cb) { _sysLog.setCallback(cb); }
size_t LogManager::getSysLogSize() { return _sysLog.getSize(); }
bool LogManager::readSysLog(String& out, size_t maxBytes) { return _sysLog.read(out, maxBytes); }
void LogManager::clearSysLog() { _sysLog.clear(); }

// ── Toggle log delegates ──
void LogManager::logToggle(ToggleSource src, bool on) { _toggleLog.log(src, on); }
size_t LogManager::getToggleLogSize() { return _toggleLog.getSize(); }
bool LogManager::readToggleLog(String& out, size_t maxBytes) { return _toggleLog.read(out, maxBytes); }
void LogManager::clearToggleLog() { _toggleLog.clear(); }

// ── Power log delegates ──
//void LogManager::logPower(unsigned long power) { _powerLog.log(power); }
void LogManager::logHourlyPower(uint8_t hour, uint32_t energyWh, time_t intervalEpoch) { _powerLog.logHourly(hour, energyWh, intervalEpoch); }
size_t LogManager::getPowerLogSize() { return _powerLog.getSize(); }
bool LogManager::readPowerLog(String& out, size_t maxBytes) { return _powerLog.read(out, maxBytes); }
void LogManager::clearPowerLog() { _powerLog.clear(); }

// ── Time delegates ──
bool LogManager::isTimeSynced() const { return _time.isTimeSynced(); }
unsigned long LogManager::getEpoch() const { return _time.getEpoch(); }

// ── Data delegates ──
const LogManager::DataFile& LogManager::getData() const { return _data.getData(); }
void LogManager::addButtonCount(uint32_t c) { _data.addButtonCount(c); }
void LogManager::addToggleCount(uint32_t c) { _data.addToggleCount(c); }
void LogManager::addTotalPower(uint64_t p) { _data.addTotalPower(p); }
void LogManager::addPumpTime(uint32_t s) { _data.addPumpTime(s); }

// ── Filesystem ──
size_t LogManager::getTotalBytes() { return LITTLEFS.totalBytes(); }
size_t LogManager::getUsedBytes() { return LITTLEFS.usedBytes(); }
