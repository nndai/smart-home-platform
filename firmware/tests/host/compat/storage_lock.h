#pragma once
#include <Arduino.h>
#include <deque>
#include <vector>
#define TASK_KEEP_INTERVAL 0
#define pdTRUE 1
struct HostQueue { size_t capacity, size; std::deque<std::vector<uint8_t>> data; };
using QueueHandle_t = HostQueue*;
inline QueueHandle_t xQueueCreate(size_t count, size_t size) { return new HostQueue{count, size, {}}; }
inline int xQueueSend(QueueHandle_t q, const void* p, uint32_t) {
    if (q->data.size() == q->capacity) return 0;
    const auto bytes = static_cast<const uint8_t*>(p);
    q->data.emplace_back(bytes, bytes + q->size); return 1;
}
inline int xQueueReceive(QueueHandle_t q, void* p, uint32_t) {
    if (q->data.empty()) return 0;
    memcpy(p, q->data.front().data(), q->size); q->data.pop_front(); return 1;
}
inline int uxQueueMessagesWaiting(QueueHandle_t q) { return q->data.size(); }
inline uint32_t (*hostCallback)() = nullptr;
inline void* sysTaskCreate(const char*, uint32_t, uint32_t (*callback)(), uint32_t, uint8_t) {
    hostCallback = callback; return reinterpret_cast<void*>(1);
}
namespace compat {
inline uint32_t newStorageIdentity() { static uint32_t next = 1000; return ++next; }
class StorageLock {
    bool held = false;
public:
    bool begin() { return true; }
    bool take() { if (held) return false; held = true; return true; }
    void give() { held = false; }
};
class StorageGuard {
    StorageLock& lock; bool owns;
public:
    StorageGuard(StorageLock& l) : lock(l), owns(l.take()) {}
    ~StorageGuard() { if (owns) lock.give(); }
    explicit operator bool() const { return owns; }
};
}
