#include "DataManager.h"

DataManager::DataManager() { memset(&_data, 0, sizeof(_data)); }

bool DataManager::begin() {
    _mounted = true;
    File f = LITTLEFS.open(FILE_PATH, "r");
    if (f) {
        if (f.size() == sizeof(DataFile)) {
            f.read((uint8_t*)&_data, sizeof(DataFile));
        }
        f.close();
    }
    return true;
}

void DataManager::_save() {
    if (!_mounted) return;
    File f = LITTLEFS.open(FILE_PATH, "w");
    if (!f) return;
    f.write((const uint8_t*)&_data, sizeof(DataFile));
    f.close();
}

void DataManager::addButtonCount(uint32_t count) { _data.buttonCount += count; _save(); }
void DataManager::addToggleCount(uint32_t count) { _data.toggleCount += count; _save(); }
void DataManager::addTotalPower(uint64_t power) { _data.totalPower += power; _save(); }
void DataManager::addPumpTime(uint32_t seconds) { _data.totalPumpSeconds += seconds; _save(); }
