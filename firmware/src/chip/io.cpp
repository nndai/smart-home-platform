#include "chip/io.h"

// ── LibreTiny: hal_gpio_* ──
#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>

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
}

// ── MCU khác: không cần ──
#else
namespace chip {
void reclaimRelayGpio() {
}
}
#endif
