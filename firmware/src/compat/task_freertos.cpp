#include "compat/task.h"

#if defined(LT_ARD_HAS_SERIAL) || defined(ARDUINO_ARCH_ESP32)

struct TaskCtx {
    SysTaskCallback_t callback;
    uint32_t intervalMs;
};

static void genericTaskWrapper(void* pvParams) {
    TaskCtx* ctx = (TaskCtx*)pvParams;
    TickType_t lastWake = xTaskGetTickCount();
    
    while (1) {
        uint32_t next = ctx->callback();
        
        if (next == TASK_DELETE) {
            delete ctx;
            vTaskDelete(NULL);
            while(1);
        } else if (next != TASK_KEEP_INTERVAL) {
            ctx->intervalMs = next;
        }
        
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(ctx->intervalMs));
    }
}

SysTaskHandle sysTaskCreate(const char* name, uint32_t intervalMs, SysTaskCallback_t callback, uint32_t stackSize, uint8_t priority) {
    TaskCtx* ctx = new TaskCtx();
    ctx->callback = callback;
    ctx->intervalMs = intervalMs;

    TaskHandle_t handle = NULL;
    xTaskCreate(genericTaskWrapper, name, stackSize, ctx, priority, &handle);
    return handle;
}

#endif
