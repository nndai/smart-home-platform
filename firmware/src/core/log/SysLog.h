#pragma once
#include "LogBase.h"
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
    struct LogQueueEntry {
        char* line;
    };

    QueueHandle_t _queue = nullptr;
    SysTaskHandle _writerTaskHandle = nullptr;
    bool _fileEnabled = true;
    uint8_t _fileLevel = LT_LEVEL_INFO;
    LogCallback _cb;

    static uint32_t _writerTaskCb();
    void _writeLine(const char* line);
    void _rotate();

    static constexpr size_t QUEUE_SIZE = 128;
    static constexpr size_t MAX_FILE_SIZE = 10 * 1024;
    static constexpr size_t MAX_LINE_LEN = 100;
    static constexpr size_t MAX_FILES = 5;
};
