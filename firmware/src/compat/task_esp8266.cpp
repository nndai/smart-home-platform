#if defined(ARDUINO_ARCH_ESP8266)

#include "compat/task.h"
#include <Arduino.h>
#include <stdlib.h>
#include <string.h>

// ──────────────────────────────────────────────────────────────────────────────
// ESP8266 (Non-OS SDK) FreeRTOS Task Shim
// ──────────────────────────────────────────────────────────────────────────────
//
// Architecture overview:
//   ESP8266 Arduino runs on the Non-OS SDK with a single cooperative
//   continuation (g_pcont) managed by loop_wrapper(). There is no preemptive
//   scheduler — WiFi/TCP processing happens in the SYS context between
//   CONT suspensions (triggered by yield()/delay()/esp_schedule()).
//
//   This shim provides real cooperative multitasking by giving each task its
//   own heap-allocated stack and performing manual Xtensa call0 ABI context
//   switching (save/restore a0, a1, a12-a15). This supports the standard
//   while(1) { work; vTaskDelay(); } pattern without modifying main.cpp.
//
//   The scheduler runs inside loop() via compatRunSchedulerStep(). Each loop()
//   call executes one round of ready tasks, then returns so loop_wrapper()
//   can call esp_schedule() to service WiFi/TCP in the SYS context.
//
// yield() override:
//   Arduino's yield() panics if SP is not within g_pcont's stack range
//   (cont_can_suspend check). Since our tasks run on separate stacks, we
//   override the weak yield() symbol: on task stacks we yield CPU back to
//   the scheduler via vTaskDelay(0); on g_pcont we delegate to __yield().
//   WiFi/TCP is serviced between loop() calls, not inside yield().
//
// Reference: ESP8266_RTOS_SDK/components/freertos/port/esp8266/port.c
//            framework-arduinoespressif8266/cores/esp8266/cont.S
// ──────────────────────────────────────────────────────────────────────────────


// ── Xtensa call0 ABI context switch ─────────────────────────────────────
// Pure assembly function — no compiler prologue/epilogue.
//   void swapContext(uint32_t* save, uint32_t* restore)
// Saves a0 (return addr), a1 (SP), a12-a15 (callee-saved) into save[0..5].
// Loads them from restore[0..5] and returns via the restored a0.
// Modeled after pxPortInitialiseStack/vPortYield in ESP8266 RTOS SDK.
extern "C" void swapContext(uint32_t* save, uint32_t* restore);

asm(
    ".section .irom0.text\n"
    ".align 4\n"
    ".global swapContext\n"
    ".type swapContext, @function\n"
    "swapContext:\n"
    "s32i a0,  a2, 0\n"     // save[0] = a0  (return address)
    "s32i a1,  a2, 4\n"     // save[1] = a1  (stack pointer)
    "s32i a12, a2, 8\n"     // save[2] = a12
    "s32i a13, a2, 12\n"    // save[3] = a13
    "s32i a14, a2, 16\n"    // save[4] = a14
    "s32i a15, a2, 20\n"    // save[5] = a15
    "l32i a0,  a3, 0\n"     // a0  = restore[0]
    "l32i a1,  a3, 4\n"     // a1  = restore[1]  (switches stack!)
    "l32i a12, a3, 8\n"     // a12 = restore[2]
    "l32i a13, a3, 12\n"    // a13 = restore[3]
    "l32i a14, a3, 16\n"    // a14 = restore[4]
    "l32i a15, a3, 20\n"    // a15 = restore[5]
    "ret\n"                  // jump to restored a0
    ".size swapContext, . - swapContext\n"
);

// ── Task descriptor ─────────────────────────────────────────────────────
struct ShimTask {
    TaskFunction_t fn;          // Task entry point
    void*          arg;         // Task parameter
    const char*    name;        // Name (for debugging)
    uint32_t       nextRunMs;   // Earliest time this task may run
    uint32_t       context[6];  // Saved Xtensa regs: a0, a1, a12-a15
    uint8_t*       stack;       // Heap-allocated stack buffer
    size_t         stackSize;   // Size of stack buffer in bytes
    bool           started;     // True after first context switch into task
    bool           finished;    // True when task function returns or deleted
};

static constexpr int MAX_SHIM_TASKS = 12;
static ShimTask g_tasks[MAX_SHIM_TASKS];
static int      g_taskCount = 0;
static int      g_currentTaskIdx = -1;   // -1 = running on g_pcont (scheduler)
static uint32_t g_schedulerCtx[6];       // Scheduler's saved context

// Stack bounds
static constexpr size_t STACK_MIN_BYTES = 768;
static constexpr size_t STACK_MAX_BYTES = 8192;

// Stack overflow detection canary
static const uint32_t STACK_CANARY_PATTERN[4] = { 0xDEADBEEF, 0xCAFEBABE, 0xBADDCAFE, 0x8BADF00D };

// ── Task trampoline ─────────────────────────────────────────────────────
// First function to execute on a new task's stack. Calls the real task
// function; if it ever returns, marks the task finished and swaps back.
static void taskTrampoline() {
    ShimTask& t = g_tasks[g_currentTaskIdx];
    t.fn(t.arg);
    // Task function returned normally (e.g. taskWifiConnect after handoff)
    t.finished = true;
    swapContext(t.context, g_schedulerCtx);
    // Never reached
}

// ── Override yield() and esp_delay() ────────────────────────────────────
// Arduino's yield() and delay() panic or do nothing if SP is not within
// g_pcont's stack range. Since our tasks run on separate stacks, we override
// the weak symbols to yield back to our cooperative scheduler.

extern "C" void __yield();
extern "C" void yield() {
    if (g_currentTaskIdx >= 0) {
        vTaskDelay(0);
    } else {
        __yield();
    }
}

extern "C" void __esp_delay(unsigned long ms);
extern "C" void esp_delay(unsigned long ms) {
    if (g_currentTaskIdx >= 0) {
        vTaskDelay(ms);
    } else {
        __esp_delay(ms);
    }
}

// ── Scheduler step ──────────────────────────────────────────────────────
// Runs one round-robin iteration of all ready tasks. Called from loop().
void compatRunSchedulerStep() {
    uint32_t now = millis();

    for (int i = 0; i < g_taskCount; i++) {
        ShimTask& t = g_tasks[i];
        
        // Catch tasks that were deleted externally before they could run
        if (t.finished && t.stack) {
            free(t.stack);
            t.stack = nullptr;
        }
        
        if (t.finished || !t.stack) continue;
        if (now < t.nextRunMs) continue;

        g_currentTaskIdx = i;

        if (!t.started) {
            // ── First run: set up synthetic initial context ──
            // When swapContext restores this, `ret` jumps to taskTrampoline
            // with SP pointing to the task's own heap-allocated stack.
            t.started = true;
            uint32_t sp = ((uint32_t)(t.stack + t.stackSize)) & ~0xFu;
            t.context[0] = (uint32_t)taskTrampoline;    // a0 = entry
            t.context[1] = sp;                          // a1 = stack top
            t.context[2] = 0;                           // a12
            t.context[3] = 0;                           // a13
            t.context[4] = 0;                           // a14
            t.context[5] = 0;                           // a15

            swapContext(g_schedulerCtx, t.context);
        } else {
            // ── Resume: task was suspended in vTaskDelay ──
            swapContext(g_schedulerCtx, t.context);
        }

        g_currentTaskIdx = -1;

        // ── Check for stack overflow (Canary) ──
        if (t.stack && !t.finished) {
            if (memcmp(t.stack, STACK_CANARY_PATTERN, sizeof(STACK_CANARY_PATTERN)) != 0) {
                Serial.printf("\r\n[FATAL] Task '%s' STACK OVERFLOW! Restarting...\r\n", 
                              t.name ? t.name : "unknown");
                delay(100);
                ESP.restart();
                while(1) {}
            }
        }

        // Reclaim stack memory of finished tasks
        if (t.finished && t.stack) {
            free(t.stack);
            t.stack = nullptr;
        }

        __yield();

        // Refresh timestamp for next task's deadline check
        now = millis();
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Public FreeRTOS-compatible API
// ═════════════════════════════════════════════════════════════════════════

BaseType_t xTaskCreate(
    TaskFunction_t pxTaskCode,
    const char*    pcName,
    uint16_t       usStackDepth,
    void*          pvParameters,
    UBaseType_t    uxPriority,
    TaskHandle_t*  pxCreatedTask)
{
    (void)uxPriority;
    
    int slot = -1;
    for (int i = 0; i < g_taskCount; i++) {
        if (g_tasks[i].stack == nullptr) {
            slot = i;
            break;
        }
    }
    
    if (slot == -1) {
        if (g_taskCount >= MAX_SHIM_TASKS) return pdFAIL;
        slot = g_taskCount;
        g_taskCount++;
    }

    ShimTask& t  = g_tasks[slot];
    t.fn         = pxTaskCode;
    t.arg        = pvParameters;
    t.name       = pcName;
    t.nextRunMs  = 0;
    t.started    = false;
    t.finished   = false;

    // Clamp stack size to conserve heap while staying safe
    size_t sz = (size_t)usStackDepth;
    if (sz < STACK_MIN_BYTES) sz = STACK_MIN_BYTES;
    if (sz > STACK_MAX_BYTES) sz = STACK_MAX_BYTES;
    t.stackSize = sz;

    t.stack = (uint8_t*)malloc(sz);
    if (!t.stack) return pdFAIL;

    // Paint with 0xA5 for stack high-water-mark debugging
    memset(t.stack, 0xA5, sz);

    // Plant Canary at the absolute bottom of the stack
    memcpy(t.stack, STACK_CANARY_PATTERN, sizeof(STACK_CANARY_PATTERN));

    if (pxCreatedTask) *pxCreatedTask = (TaskHandle_t)&t;
    return pdPASS;
}

void vTaskDelay(TickType_t xTicksToDelay) {
    if (g_currentTaskIdx >= 0 && g_currentTaskIdx < g_taskCount) {
        ShimTask& t = g_tasks[g_currentTaskIdx];
        t.nextRunMs = millis() + xTicksToDelay;
        // Yield CPU back to the scheduler; resumes here when re-scheduled
        swapContext(t.context, g_schedulerCtx);
    } else {
        // Outside a task (e.g. during setup) — use real delay
        delay(xTicksToDelay);
    }
}

void vTaskDelayUntil(TickType_t* pxPreviousWakeTime, TickType_t xTimeIncrement) {
    TickType_t now = millis();
    TickType_t target = (pxPreviousWakeTime ? *pxPreviousWakeTime : now)
                        + xTimeIncrement;
    if (pxPreviousWakeTime) *pxPreviousWakeTime = target;

    int32_t delayMs = (int32_t)(target - now);
    if (delayMs < 1) delayMs = 1;
    vTaskDelay((TickType_t)delayMs);
}

TickType_t xTaskGetTickCount() {
    return millis();
}

void vTaskDelete(TaskHandle_t xTaskToDelete) {
    if (xTaskToDelete == NULL) {
        if (g_currentTaskIdx < 0) {
            // Called from loop(): this is the scheduler entry point
            compatRunSchedulerStep();
        } else {
            // Task deleting itself (e.g. taskWifiConnect after handoff)
            ShimTask& t = g_tasks[g_currentTaskIdx];
            t.finished = true;
            swapContext(t.context, g_schedulerCtx);
            // Never reached
        }
    } else {
        ShimTask* t = (ShimTask*)xTaskToDelete;
        t->finished = true;
    }
}

// ═════════════════════════════════════════════════════════════════════════
// Queue — simple ring buffer for inter-task communication
// ═════════════════════════════════════════════════════════════════════════

struct ShimQueue {
    uint8_t* buffer;
    size_t   itemSize;
    size_t   capacity;
    size_t   head;
    size_t   tail;
    size_t   count;
};

QueueHandle_t xQueueCreate(UBaseType_t uxQueueLength, UBaseType_t uxItemSize) {
    ShimQueue* q = (ShimQueue*)malloc(sizeof(ShimQueue));
    if (!q) return nullptr;
    q->buffer = (uint8_t*)malloc(uxQueueLength * uxItemSize);
    if (!q->buffer) { free(q); return nullptr; }
    q->itemSize  = uxItemSize;
    q->capacity  = uxQueueLength;
    q->head = q->tail = q->count = 0;
    return (QueueHandle_t)q;
}

BaseType_t xQueueSend(QueueHandle_t xQueue, const void* pvItemToQueue,
                      TickType_t xTicksToWait) {
    (void)xTicksToWait;
    if (!xQueue || !pvItemToQueue) return pdFAIL;
    ShimQueue* q = (ShimQueue*)xQueue;
    if (q->count >= q->capacity) return pdFAIL;
    memcpy(q->buffer + (q->tail * q->itemSize), pvItemToQueue, q->itemSize);
    q->tail = (q->tail + 1) % q->capacity;
    q->count++;
    return pdPASS;
}

BaseType_t xQueueReceive(QueueHandle_t xQueue, void* pvBuffer,
                         TickType_t xTicksToWait) {
    if (!xQueue || !pvBuffer) return pdFAIL;
    ShimQueue* q = (ShimQueue*)xQueue;
    uint32_t start = millis();
    while (q->count == 0) {
        if (xTicksToWait != portMAX_DELAY) {
            if ((millis() - start) >= xTicksToWait) return pdFAIL;
        }
        vTaskDelay(10);
    }
    memcpy(pvBuffer, q->buffer + (q->head * q->itemSize), q->itemSize);
    q->head = (q->head + 1) % q->capacity;
    q->count--;
    return pdPASS;
}

// ═════════════════════════════════════════════════════════════════════════
// Task Statistics
// ═════════════════════════════════════════════════════════════════════════

UBaseType_t uxTaskGetNumberOfTasks(void) {
    UBaseType_t count = 0;
    for (int i = 0; i < g_taskCount; i++) {
        if (g_tasks[i].stack != nullptr && !g_tasks[i].finished) {
            count++;
        }
    }
    return count;
}

UBaseType_t uxTaskGetStackHighWaterMark(TaskHandle_t xTask) {
    ShimTask* t = xTask ? (ShimTask*)xTask : (g_currentTaskIdx >= 0 ? &g_tasks[g_currentTaskIdx] : nullptr);
    if (!t || !t->stack) return 0;
    
    // Count contiguous 0xA5 bytes just above the canary
    size_t freeBytes = 0;
    for (size_t i = sizeof(STACK_CANARY_PATTERN); i < t->stackSize; i++) {
        if (t->stack[i] == 0xA5) {
            freeBytes++;
        } else {
            break;
        }
    }
    // Return total untouched bytes including the intact canary
    return (UBaseType_t)(freeBytes);
}

UBaseType_t uxTaskGetSystemState(TaskStatus_t* pxTaskStatusArray, UBaseType_t uxArraySize, uint32_t* pulTotalRunTime) {
    if (pulTotalRunTime) *pulTotalRunTime = 0; // Not supported
    
    UBaseType_t count = 0;
    for (int i = 0; i < g_taskCount; i++) {
        if (count >= uxArraySize) break;
        ShimTask& t = g_tasks[i];
        if (t.stack == nullptr) continue; // Skip unused slots
        
        pxTaskStatusArray[count].pcTaskName = t.name ? t.name : "unknown";
        pxTaskStatusArray[count].uxCurrentPriority = 1; // Dummy priority
        pxTaskStatusArray[count].usStackHighWaterMark = (uint16_t)uxTaskGetStackHighWaterMark((TaskHandle_t)&t);
        
        if (t.finished) {
            pxTaskStatusArray[count].eCurrentState = eDeleted;
        } else if (i == g_currentTaskIdx) {
            pxTaskStatusArray[count].eCurrentState = eRunning;
        } else {
            pxTaskStatusArray[count].eCurrentState = eReady;
        }
        count++;
    }
    return count;
}

#endif // ARDUINO_ARCH_ESP8266
