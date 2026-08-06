#pragma once
#include <Arduino.h>
#include <FS.h>
#include <LittleFS.h>

class DataManager {
public:
    struct DataFile {
        uint32_t buttonCount;
        uint32_t toggleCount;
        uint64_t totalPower;
        uint32_t totalPumpSeconds;
    };

    DataManager();
    bool begin();
    const DataFile& getData() const { return _data; }
    void addButtonCount(uint32_t count = 1);
    void addToggleCount(uint32_t count = 1);
    void addTotalPower(uint64_t power);
    void addPumpTime(uint32_t seconds);

private:
    DataFile _data;
    bool _mounted = false;
    void _save();
    static constexpr const char* FILE_PATH = "/logs/data.bin";
};
