#pragma once

#include <Arduino.h>

namespace chip {
// ── Chip anchor: UID phần cứng không đọc được từ dump flash ──
// LN882H: hal_flash_read_unique_id() — 16B UID trong OTP die flash
// ESP32 : esp_efuse_mac_get_default() — 6B eFuse (không override được)
// Trả về số byte thực tế (<= 16).
size_t anchorBytes(uint8_t out[16]);

// ── Random an toàn (CSPRNG phần cứng) ──
void randomBytes(uint8_t* out, size_t len);
}
