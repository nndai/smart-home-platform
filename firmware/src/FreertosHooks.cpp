#include <Arduino.h>
#include <FreeRTOS.h>
#include <task.h>

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
