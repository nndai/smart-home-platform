#pragma once

// ── FreeRTOS include đa nền tảng ──
#if defined(LT_ARD_HAS_SERIAL)
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
#define compatRunSchedulerStep() vTaskDelete(NULL)
#elif defined(ARDUINO_ARCH_ESP32)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/queue.h>
#include <freertos/semphr.h>
#define compatRunSchedulerStep() vTaskDelete(NULL)

// Provide a weak mock for ESP32 if CONFIG_FREERTOS_USE_TRACE_FACILITY is not enabled
#if !defined(configUSE_TRACE_FACILITY) || configUSE_TRACE_FACILITY == 0
#ifdef __cplusplus
extern "C" {
#endif
__attribute__((weak)) UBaseType_t uxTaskGetSystemState(TaskStatus_t *pxTaskStatusArray, UBaseType_t uxArraySize, uint32_t *pulTotalRunTime) {
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
#include <Arduino.h>

typedef void (*TaskFunction_t)(void*);
typedef void* TaskHandle_t;
typedef void* QueueHandle_t;
typedef void* SemaphoreHandle_t;
typedef uint32_t TickType_t;
typedef int32_t BaseType_t;
typedef uint32_t UBaseType_t;

#define pvPortMalloc malloc
#define vPortFree free

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
    eTaskState eCurrentState;
    UBaseType_t uxCurrentPriority;
    uint16_t usStackHighWaterMark;
} TaskStatus_t;

#define pdPASS 1
#define pdFAIL 0
#define pdTRUE 1
#define pdFALSE 0
#define pdMS_TO_TICKS(ms) (ms)
#define portTICK_PERIOD_MS 1
#define tskIDLE_PRIORITY 0

#define portMAX_DELAY 0xFFFFFFFF

BaseType_t xTaskCreate(TaskFunction_t pxTaskCode, const char* pcName, uint16_t usStackDepth, void* pvParameters, UBaseType_t uxPriority, TaskHandle_t* pxCreatedTask);
void vTaskDelay(TickType_t xTicksToDelay);
void vTaskDelayUntil(TickType_t* pxPreviousWakeTime, TickType_t xTimeIncrement);
void vTaskDelete(TaskHandle_t xTaskToDelete);
TickType_t xTaskGetTickCount();

UBaseType_t uxTaskGetNumberOfTasks(void);
UBaseType_t uxTaskGetSystemState(TaskStatus_t* pxTaskStatusArray, UBaseType_t uxArraySize, uint32_t* pulTotalRunTime);
UBaseType_t uxTaskGetStackHighWaterMark(TaskHandle_t xTask);

QueueHandle_t xQueueCreate(UBaseType_t uxQueueLength, UBaseType_t uxItemSize);
BaseType_t xQueueSend(QueueHandle_t xQueue, const void* pvItemToQueue, TickType_t xTicksToWait);
BaseType_t xQueueReceive(QueueHandle_t xQueue, void* pvBuffer, TickType_t xTicksToWait);

void compatRunSchedulerStep();

#else
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
#define compatRunSchedulerStep() vTaskDelete(NULL)
#endif
