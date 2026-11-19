#pragma once
#include "task.h"
#if defined(ARDUINO_ARCH_ESP32)
#include <esp_system.h>
#endif

namespace compat {
inline uint32_t newStorageIdentity() {
#if defined(ARDUINO_ARCH_ESP32)
    const uint32_t value = esp_random();
#else
    const uint32_t value = ESP.random();
#endif
    return value ? value : 1;
}
// Never wait from a control or MQTT callback. A busy store can be retried later.
class StorageLock {
#if defined(ARDUINO_ARCH_ESP8266)
    bool _held = false;
public:
    bool begin() { return true; }
    bool take() { if (_held) return false; _held = true; return true; }
    void give() { _held = false; }
#else
    SemaphoreHandle_t _mutex = nullptr;
public:
    bool begin() { _mutex = xSemaphoreCreateMutex(); return _mutex != nullptr; }
    bool take() { return _mutex && xSemaphoreTake(_mutex, 0) == pdTRUE; }
    void give() { xSemaphoreGive(_mutex); }
#endif
};
class StorageGuard {
    StorageLock& _lock;
    bool _owns;
public:
    explicit StorageGuard(StorageLock& lock) : _lock(lock), _owns(lock.take()) {}
    ~StorageGuard() { if (_owns) _lock.give(); }
    explicit operator bool() const { return _owns; }
};
}
