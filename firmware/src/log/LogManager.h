#pragma once
#include "TimeManager.h"
#include "DataManager.h"
#include "SysLog.h"
#include "ToggleLog.h"
#include "PowerLog.h"

class LogManager {
public:
    using LogCallback = SysLog::LogCallback;
    using ToggleSource = ToggleLog::Source;
    using DataFile = DataManager::DataFile;

    static constexpr ToggleSource TOGGLE_BUTTON = ToggleLog::TOGGLE_BUTTON;
    static constexpr ToggleSource TOGGLE_ONLINE = ToggleLog::TOGGLE_ONLINE;
    static constexpr ToggleSource TOGGLE_SCHEDULE = ToggleLog::TOGGLE_SCHEDULE;

    LogManager();

    bool begin();
    void maintenance();

    // System log
    void ingest(const char* line);
    void writeFile(const char* line);
    void setSysLogFileEnabled(bool en);
    void setSysLogFileLevel(uint8_t lv);
    void setLogCallback(LogCallback cb);
    size_t getSysLogSize();
    bool readSysLog(String& out, size_t maxBytes = 4096);
    void clearSysLog();

    // Toggle log
    void logToggle(ToggleSource src, bool on);
    size_t getToggleLogSize();
    bool readToggleLog(String& out, size_t maxBytes = 4096);
    void clearToggleLog();

    // Power log
    void logPower(unsigned long power);
    void logHourlyPower(uint8_t hour, uint32_t energyWh, time_t intervalEpoch = 0);
    size_t getPowerLogSize();
    bool readPowerLog(String& out, size_t maxBytes = 4096);
    void clearPowerLog();

    // Time
    void setTime(unsigned long epoch);
    bool isTimeSynced() const;
    unsigned long getEpoch() const;

    // Data
    const DataFile& getData() const;
    void addButtonCount(uint32_t count = 1);
    void addToggleCount(uint32_t count = 1);
    void addTotalPower(uint64_t power);
    void addPumpTime(uint32_t seconds);

    // Filesystem
    size_t getTotalBytes();
    size_t getUsedBytes();

private:
    TimeManager _time;
    DataManager _data;
    SysLog _sysLog;
    ToggleLog _toggleLog;
    PowerLog _powerLog;
};
