#if defined(ARDUINO_ARCH_ESP8266)

#include "compat/task.h"
#define _TASK_OO_CALLBACKS
#include <TaskScheduler.h>

Scheduler runner;

class SysTaskWrapper : public Task {
    SysTaskCallback_t sysCallback;
public:
    SysTaskWrapper(uint32_t intervalMs, SysTaskCallback_t cb) 
        : Task(intervalMs, TASK_FOREVER, &runner, false), sysCallback(cb) {
        setSchedulingOption(TASK_SCHEDULE_NC);
        enable();
    }
    
    bool Callback() override {
        uint32_t next = sysCallback();
        if (next == TASK_DELETE) {
            this->disable();
        } else if (next != TASK_KEEP_INTERVAL) {
            this->setInterval(next);
        }
        return true;
    }
};

SysTaskHandle sysTaskCreate(const char* name, uint32_t intervalMs, SysTaskCallback_t callback, uint32_t stackSize, uint8_t priority) {
    (void)name;
    (void)stackSize;
    (void)priority;
    SysTaskWrapper* t = new SysTaskWrapper(intervalMs, callback);
    return (SysTaskHandle)t;
}

void sysTaskSetInterval(SysTaskHandle handle, uint32_t intervalMs) {
    if (!handle) return;
    SysTaskWrapper* t = (SysTaskWrapper*)handle;
    t->setInterval(intervalMs);
}

void sysTaskDelete(SysTaskHandle handle) {
    if (!handle) return;
    SysTaskWrapper* t = (SysTaskWrapper*)handle;
    t->disable();
    // Do not delete t here to avoid crashing TaskScheduler if called within callback
}

void compatRunSchedulerStep() {
    runner.execute();
}

void vTaskDelay(uint32_t xTicksToDelay) {
    delay(xTicksToDelay);
}

// ═════════════════════════════════════════════════════════════════════════
// Queue — simple ring buffer for inter-task communication (non-blocking)
// ═════════════════════════════════════════════════════════════════════════

struct ShimQueue {
    uint8_t* buffer;
    size_t   itemSize;
    size_t   capacity;
    size_t   head;
    size_t   tail;
    size_t   count;
};

QueueHandle_t xQueueCreate(uint32_t uxQueueLength, uint32_t uxItemSize) {
    ShimQueue* q = (ShimQueue*)malloc(sizeof(ShimQueue));
    if (!q) return nullptr;
    q->buffer = (uint8_t*)malloc(uxQueueLength * uxItemSize);
    if (!q->buffer) { free(q); return nullptr; }
    q->itemSize  = uxItemSize;
    q->capacity  = uxQueueLength;
    q->head = q->tail = q->count = 0;
    return (QueueHandle_t)q;
}

int xQueueSend(QueueHandle_t xQueue, const void* pvItemToQueue, uint32_t xTicksToWait) {
    (void)xTicksToWait;
    if (!xQueue || !pvItemToQueue) return pdFAIL;
    ShimQueue* q = (ShimQueue*)xQueue;
    if (q->count >= q->capacity) return pdFAIL;
    memcpy(q->buffer + (q->tail * q->itemSize), pvItemToQueue, q->itemSize);
    q->tail = (q->tail + 1) % q->capacity;
    q->count++;
    return pdPASS;
}

int xQueueReceive(QueueHandle_t xQueue, void* pvBuffer, uint32_t xTicksToWait) {
    if (!xQueue || !pvBuffer) return pdFAIL;
    ShimQueue* q = (ShimQueue*)xQueue;
    
    // In TaskScheduler, we CANNOT block using delay or yield. We must return immediately if empty.
    if (q->count == 0) {
        return pdFAIL;
    }
    
    memcpy(pvBuffer, q->buffer + (q->head * q->itemSize), q->itemSize);
    q->head = (q->head + 1) % q->capacity;
    q->count--;
    return pdPASS;
}

int uxQueueMessagesWaiting(QueueHandle_t xQueue) {
    if (!xQueue) return 0;
    ShimQueue* q = (ShimQueue*)xQueue;
    return q->count;
}

#endif
