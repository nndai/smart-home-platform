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

const char* chipModelName() {
    String cpu_name = lt_cpu_get_model_code();
    cpu_name.toUpperCase();
    return cpu_name.c_str();
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
#include <ESP.h>

namespace chip {
    void reclaimRelayGpio() {
    }

    float readWifiTempC() {
        return 0.0f;
    }

#if defined(ARDUINO_ARCH_ESP8266)
    const char* chipModelName() {
        return "ESP8266";
    }
#else
    const char* chipModelName() {
        static String model = ESP.getChipModel();
        return model.c_str();
    }
#endif

    uint32_t systemChipId() {
        return (uint32_t)(ESP.getEfuseMac() >> 32);
    }

    const char* systemResetReason() {
        esp_reset_reason_t reason = esp_reset_reason();
        switch (reason) {
        case ESP_RST_UNKNOWN:
            return "UNKNOWN";
        case ESP_RST_POWERON:
            return "POWERON";
        case ESP_RST_EXT:
            return "EXT";
        case ESP_RST_SW:
            return "SW";
        case ESP_RST_PANIC:
            return "PANIC";
        case ESP_RST_INT_WDT:
            return "INT_WDT";
        case ESP_RST_TASK_WDT:
            return "TASK_WDT";
        case ESP_RST_WDT:
            return "WDT";
        case ESP_RST_DEEPSLEEP:
            return "DEEPSLEEP";
        case ESP_RST_BROWNOUT:
            return "BROWNOUT";
        case ESP_RST_SDIO:
            return "SDIO";
        default:
            return "UNKNOWN";
        }
    }

    size_t heapMinFree() {
        return ESP.getMinFreeHeap();
    }
}
#endif
