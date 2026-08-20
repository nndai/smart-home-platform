#include "SysLog.h"
#include "Config.h"
#include <time.h>

SysLog* g_sysLogInstance = nullptr;

SysLog::SysLog(TimeManager& tm) : LogBase(tm, DIR) {}

bool SysLog::begin() {
    _mounted = true;
    _queue = xQueueCreate(QUEUE_SIZE, sizeof(LogQueueEntry));
    if (_queue) {
        // Use a global pointer for the static callback
        g_sysLogInstance = this;
        sysTaskCreate("logWriter", 50, _writerTaskCb, TASK_LOGWRITER_STACK, TASK_LOGWRITER_PRIO);
    }
    return true;
}

void SysLog::ingest(const char* line) {
    if (!line) return;
    if (_cb) _cb(String(line));
    writeFile(line);
}

void SysLog::writeFile(const char* line) {
    if (!line || !_mounted || !_fileEnabled || !_queue) return;

    // Nhận diện level từ prefix: dòng LT_ dạng "[I][SYS] ..." / "[E] ...",
    // hoặc format cũ "I ..."/"E ..." (log_capture LN882H).
    uint8_t level = LT_LEVEL_INFO;
    const char* p = line;
    if (line[0] == '[' && line[2] == ']') {
        p = line + 1;
    }
    char c = p[0];
    if (c == 'T') level = LT_LEVEL_TRACE;
    else if (c == 'D') level = LT_LEVEL_DEBUG;
    else if (c == 'I') level = LT_LEVEL_INFO;
    else if (c == 'W') level = LT_LEVEL_WARN;
    else if (c == 'E') level = LT_LEVEL_ERROR;
    else if (c == 'F') level = LT_LEVEL_FATAL;

    if (level < _fileLevel) return;

    char* lineCopy = strdup(line);
    if (!lineCopy) return;

    LogQueueEntry entry = {lineCopy};
    if (xQueueSend(_queue, &entry, 0) != pdTRUE) {
        free(lineCopy);
    }
}

void SysLog::setEnabled(bool en) { _fileEnabled = en; }
void SysLog::setLevel(uint8_t lv) { _fileLevel = lv; }
void SysLog::setCallback(LogCallback cb) { _cb = cb; }

uint32_t SysLog::_writerTaskCb() {
    if (!g_sysLogInstance) return TASK_KEEP_INTERVAL;
    
    LogQueueEntry entry;
    // Process up to 5 entries per tick to prevent blocking TaskScheduler
    for (int i = 0; i < 5; i++) {
        if (xQueueReceive(g_sysLogInstance->_queue, &entry, 0) == pdTRUE) {
            if (entry.line) {
                g_sysLogInstance->_writeLine(entry.line);
                free(entry.line);
            }
        } else {
            break;
        }
    }
    return TASK_KEEP_INTERVAL;
}

void SysLog::_writeLine(const char* line) {
    if (!_mounted) return;

    String path = String(DIR) + _time.dateStr() + ".log";

    _rotate();

    File fCheck = LITTLEFS.open(path, "r");
    if (fCheck) {
        if (fCheck.size() >= MAX_FILE_SIZE) {
            fCheck.close();
            return;
        }
        fCheck.close();
    }

    File f = LITTLEFS.open(path, "a");
    if (!f) return;

    String ts = _time.ts();
    f.print(ts);
    f.print(" ");
    f.print(line);
    f.print("\n");

    f.close();
}

void SysLog::_rotate() {
    while (true) {
        String files[MAX_FILES + 1];
        int count = _listFiles(DIR, files, MAX_FILES + 1);
        if (count < MAX_FILES + 1) break;
        String oldest = _oldestFile(DIR);
        if (oldest.length() == 0) break;
        LITTLEFS.remove(oldest);
    }
}

void SysLog::migrateNosync(unsigned long epoch) {
    if (!_mounted) return;
    _migrateNosync(DIR, epoch, MAX_FILE_SIZE);
}
