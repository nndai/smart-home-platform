#pragma once

#include <Arduino.h>

#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>
#endif

namespace compat {
// Tắt clock khối ngoại vi không dùng (chỉ LibreTiny có; MCU khác no-op)
inline void pmDisableUnusedClocks() {
#if defined(LT_ARD_HAS_SERIAL)
    ln_pm_always_clk_disable_select(CLK_G_I2S | CLK_G_WS2811 | CLK_G_SDIO | CLK_G_AES);
#endif
}
}
