#pragma once
#include "LogBase.h"

#include <Config.h>
#include <FS.h>
#include <LittleFS.h>
#include "compat/log.h"
#include "compat/task.h"
#include <functional>

class SysLog : public LogBase {
public:
    using LogCallback = std::function<void(const String& line)>;

    SysLog(TimeManager& tm);
    bool begin() override;

    void ingest(const char* line);
    void writeFile(const char* line);
    void setEnabled(bool en);
    void setLevel(uint8_t lv);
    void setCallback(LogCallback cb);

    void migrateNosync(unsigned long epoch);

    static constexpr const char* DIR = "/logs/sys/";

private:
    bool _fileEnabled = true;
    uint8_t _fileLevel = LT_LEVEL_INFO;
    LogCallback _cb;
};
