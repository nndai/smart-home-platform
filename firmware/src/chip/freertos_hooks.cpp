// Chỉ LibreTiny cần hook này (ESP32 core đã tự định nghĩa)
#if defined(LT_ARD_HAS_SERIAL)

#include <Arduino.h>
#include "compat/log.h"
#include "compat/task.h"

extern "C" void vApplicationMallocFailedHook(void) {
    printf("[FATAL] Malloc Failed\n");
    LT_E("Malloc Failed\n");
    delay(1000);
    ESP.restart();
}

extern "C" void vApplicationStackOverflowHook(TaskHandle_t xTask, char *pcTaskName) {
    printf("[FATAL] (%s) Stack Overflow\n", pcTaskName);
    LT_E("(%s) Stack Overflow\n", pcTaskName);
    delay(1000);
    ESP.restart();
}

#endif
