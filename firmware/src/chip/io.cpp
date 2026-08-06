#include "chip/io.h"

// ── LibreTiny: hal_gpio_* ──
#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>

extern "C" uint16_t cal_adc_read(adc_ch_t ch);

namespace chip {
void reclaimRelayGpio() {
    uint32_t bases[2] = { GPIOB_BASE, GPIOA_BASE };
    gpio_pin_t pins[2] = { GPIO_PIN_3, GPIO_PIN_8 };
    for (int i = 0; i < 2; i++) {
        hal_gpio_pin_afio_en(bases[i], pins[i], HAL_DISABLE);
        gpio_init_t_def cfg;
        cfg.pin = pins[i];
        cfg.speed = GPIO_NORMAL_SPEED;
        cfg.mode = GPIO_MODE_DIGITAL;
        cfg.dir = GPIO_OUTPUT;
        cfg.pull = GPIO_PULL_NONE;
        hal_gpio_init(bases[i], &cfg);
        hal_gpio_pin_reset(bases[i], pins[i]);
    }
}

float readWifiTempC() {
    return 25.0f + (cal_adc_read(ADC_CH0) - 770.0f) / 2.54f;
}

uint32_t systemChipId() {
    return ESP.getChipId();
}

const char* systemResetReason() {
    return ESP.getResetReason().c_str();
}

size_t heapMinFree() {
    return (size_t)lt_heap_get_min_free();
}
}

// ── MCU khác: không cần ──
#else
#include <esp_system.h>
#include <esp_heap_caps.h>

namespace chip {
void reclaimRelayGpio() {
}

float readWifiTempC() {
    return 0.0f;
}

uint32_t systemChipId() {
    return (uint32_t)(ESP.getEfuseMac() >> 32);
}

const char* systemResetReason() {
    static char buf[16];
    snprintf(buf, sizeof(buf), "%d", (int)esp_reset_reason());
    return buf;
}

size_t heapMinFree() {
    return heap_caps_get_minimum_free_size(MALLOC_CAP_DEFAULT);
}
}
#endif
