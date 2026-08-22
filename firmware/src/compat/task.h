#pragma once

#include <Arduino.h>

// ── Unified Task API ──

// Special return values for SysTaskCallback_t
#define TASK_DELETE 0xFFFFFFFF
#define TASK_KEEP_INTERVAL 0

// A task callback returns the next interval in ms. 
// Return TASK_KEEP_INTERVAL to keep the current interval.
// Return TASK_DELETE to terminate the task.
typedef uint32_t (*SysTaskCallback_t)();

typedef void* SysTaskHandle;

#if defined(LT_ARD_HAS_SERIAL) || defined(ARDUINO_ARCH_ESP32)

// ── FreeRTOS Implementation (ESP32 / LN882H) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
#elif defined(ARDUINO_ARCH_ESP32)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#endif

// We still keep the original FreeRTOS API around for existing drivers/libs
#define compatRunSchedulerStep() vTaskDelete(NULL)

// Provide a weak mock for ESP32 if CONFIG_FREERTOS_USE_TRACE_FACILITY is not enabled
#if !defined(configUSE_TRACE_FACILITY) || configUSE_TRACE_FACILITY == 0
#ifdef __cplusplus
extern "C" {
#endif
    __attribute__((weak)) UBaseType_t uxTaskGetSystemState(TaskStatus_t* pxTaskStatusArray, UBaseType_t uxArraySize, uint32_t* pulTotalRunTime) {
        (void)pxTaskStatusArray;
        (void)uxArraySize;
        if (pulTotalRunTime) *pulTotalRunTime = 0;
        return 0;
    }
#ifdef __cplusplus
}
#endif
#endif

#elif defined(ARDUINO_ARCH_ESP8266)

// ── TaskScheduler Implementation (ESP8266) ──
#define pvPortMalloc malloc
#define vPortFree free

#define pdPASS 1
#define pdFAIL 0
#define pdTRUE 1
#define pdFALSE 0
#define pdMS_TO_TICKS(ms) (ms)
#define portMAX_DELAY 0xFFFFFFFF
#define _TASK_SCHEDULING_OPTIONS

typedef uint32_t UBaseType_t;

typedef enum eTaskState {
    eRunning = 0,
    eReady,
    eBlocked,
    eSuspended,
    eDeleted,
    eInvalid
} eTaskState;

typedef struct xTASK_STATUS {
    const char* pcTaskName;
    UBaseType_t uxCurrentPriority;
    eTaskState eCurrentState;
    uint32_t usStackHighWaterMark;
} TaskStatus_t;

inline UBaseType_t uxTaskGetNumberOfTasks() {
    return 1;
}

inline UBaseType_t uxTaskGetSystemState(TaskStatus_t *pxTaskStatusArray, UBaseType_t uxArraySize, uint32_t *pulTotalRunTime) {
    if (uxArraySize > 0 && pxTaskStatusArray != nullptr) {
        pxTaskStatusArray[0].pcTaskName = "loop";
        pxTaskStatusArray[0].uxCurrentPriority = 1;
        pxTaskStatusArray[0].eCurrentState = eRunning;
        pxTaskStatusArray[0].usStackHighWaterMark = ESP.getFreeContStack();
        if (pulTotalRunTime) *pulTotalRunTime = 0;
        return 1;
    }
    return 0;
}

typedef void* QueueHandle_t;
QueueHandle_t xQueueCreate(uint32_t uxQueueLength, uint32_t uxItemSize);
int xQueueSend(QueueHandle_t xQueue, const void* pvItemToQueue, uint32_t xTicksToWait);
int xQueueReceive(QueueHandle_t xQueue, void* pvBuffer, uint32_t xTicksToWait);
int uxQueueMessagesWaiting(QueueHandle_t xQueue);

// Delay is not supported inside TaskScheduler tasks, but for compatibility outside tasks:
void vTaskDelay(uint32_t xTicksToDelay);

void compatRunSchedulerStep();

#endif

// Unified task creator
SysTaskHandle sysTaskCreate(const char* name, uint32_t intervalMs, SysTaskCallback_t callback, uint32_t stackSize, uint8_t priority);
