#include "core/DeviceIdentity.h"

#include "core/Crypto.h" // mbedtls md/gcm + chip/anchor.h
#include "compat/kv.h"
#include "compat/log.h"

void DeviceIdentity::begin(const char* model) {
    if (!model || strlen(model) == 0) model = "device";
    strlcpy(_model, model, sizeof(_model));

    _anchorLen = chip::anchorBytes(_anchor);

    // deviceId = "dev-" + 6 byte đầu (12 hex) của SHA-256(toàn bộ anchor):
    // gộp đủ entropy giữa các nền MCU (LN882H 16B flash ID / ESP32 6B MAC),
    // deterministic, format thống nhất 16 ký tự, không cần persist.
    uint8_t anchorHash[32];
    const mbedtls_md_info_t* hashMd = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    mbedtls_md(hashMd, _anchor, _anchorLen, anchorHash);

    char anchorHex[13];
    _hex(anchorHash, 6, anchorHex);

    memcpy(_deviceId, "dev-", 4);
    memcpy(_deviceId + 4, anchorHex, 12);
    _deviceId[16] = '\0';

    snprintf(_apSSID, sizeof(_apSSID), "myhome-%s-%.4s", _model, anchorHex);

    LT_IM(CFG, "Identity: deviceId=%s apSSID=%s", _deviceId, _apSSID);
}

void DeviceIdentity::setEncSeed(const char* seed) {
    // Key mã hóa blob = SHA-256(deviceId + FW_SECRET) — cùng seed với mqttPass
    // (ConfigManager::setEncSeed). Không bao giờ lưu key xuống flash.
    _hasEncKey = crypto::cfgKeyFromSeed(seed, _encKey);
    if (!_hasEncKey) {
        LT_EM(CFG, "Identity: empty enc seed, keys cannot be stored!");
        return;
    }

    if (!_load()) {
        LT_IM(CFG, "Identity: no valid keys, generating new ones");
        _generateKeys();
        if (!_save()) {
            LT_EM(CFG, "Identity: failed to save keys!");
        }
    }
}

bool DeviceIdentity::controlKeyHex(String& out) const {
    if (!_hasControlKey) return false;
    char hex[KEY_LEN * 2 + 1];
    _hex(_controlKey, KEY_LEN, hex);
    out = hex;
    return true;
}

bool DeviceIdentity::setControlKeyHex(const char* hex) {
    size_t n = 0;
    if (!_unhex(hex, _controlKey, KEY_LEN, &n) || n != KEY_LEN) return false;
    _hasControlKey = true;
    return _save();
}

void DeviceIdentity::reset() {
    _hasControlKey = false;
    memset(_controlKey, 0, sizeof(_controlKey));
    compat::kvDel(_kvKey());
}

bool DeviceIdentity::_load() {
    if (!_hasEncKey) return false;

    uint8_t blob[BLOB_LEN];
    size_t storedLen = 0;
    KvError err = compat::kvGet(_kvKey(), blob, sizeof(blob), &storedLen);
    if (err != KvError::Ok || storedLen != sizeof(blob)) {
        return false; // chưa có / hỏng / blob của thiết bị khác
    }

    if (!crypto::aesGcmDecrypt(_encKey, blob, _controlKey, KEY_LEN)) return false;

    _hasControlKey = true;
    return true;
}

bool DeviceIdentity::_save() const {
    if (!_hasEncKey) return false;

    uint8_t blob[BLOB_LEN];
    if (!crypto::aesGcmEncrypt(_encKey, _controlKey, KEY_LEN, blob)) return false;

    return compat::kvSet(_kvKey(), blob, sizeof(blob)) == KvError::Ok;
}

void DeviceIdentity::_generateKeys() {
    chip::randomBytes(_controlKey, KEY_LEN);
    _hasControlKey = true;
}

void DeviceIdentity::_hex(const uint8_t* data, size_t len, char* out) {
    static const char* d = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        out[i * 2] = d[data[i] >> 4];
        out[i * 2 + 1] = d[data[i] & 0x0F];
    }
    out[len * 2] = '\0';
}

bool DeviceIdentity::_unhex(const char* hex, uint8_t* out, size_t maxLen, size_t* outLen) {
    if (!hex) return false;
    size_t n = strlen(hex);
    if (n % 2 != 0 || n / 2 > maxLen) return false;
    for (size_t i = 0; i < n / 2; i++) {
        auto val = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        };
        int h = val(hex[i * 2]);
        int l = val(hex[i * 2 + 1]);
        if (h < 0 || l < 0) return false;
        out[i] = (uint8_t)((h << 4) | l);
    }
    if (outLen) *outLen = n / 2;
    return true;
}