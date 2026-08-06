#pragma once

#include <Arduino.h>
#include <functional>
#include <Config.h>

class OTAManager {
public:
    using ProgressCallback = std::function<void(int progress, int total)>;
    using ResultCallback = std::function<void(bool success, const char* message)>;

    OTAManager();
    bool begin();
    void handle();
    bool startFromUrl(const String& url, ProgressCallback onProgress = nullptr, ResultCallback onResult = nullptr);
    bool startFromStream(size_t size, ProgressCallback onProgress = nullptr, ResultCallback onResult = nullptr);
    bool writeChunk(const uint8_t* data, size_t len);
    bool end();
    bool abort();
    void writeError();
    bool isRunning() const { return _running; }
    int getProgress() const { return _progress; }
    int getTotal() const { return _total; }
    bool canRollback();
    bool rollback();

private:
    bool _running;
    bool _urlMode;
    int _progress;
    int _total;
    size_t _written;
    ProgressCallback _onProgress;
    ResultCallback _onResult;
};
