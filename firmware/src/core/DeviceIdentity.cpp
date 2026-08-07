#include "core/DeviceIdentity.h"

#include <mbedtls/gcm.h>
#include <mbedtls/md.h>

#include "compat/kv.h"
#include "compat/log.h"
#include "chip/anchor.h"

void DeviceIdentity::begin(const char* model) {
    if (!model || strlen(model) == 0) model = "device";
    strlcpy(_model, model, sizeof(_model));

    _anchorLen = chip::anchorBytes(_anchor);

    char anchorHex[33];
    _hex(_anchor, _anchorLen, anchorHex);

    memcpy(_deviceId, "dev-", 4);
    memcpy(_deviceId + 4, anchorHex, 12);
    _deviceId[16] = '\0';

    snprintf(_apSSID, sizeof(_apSSID), "myhome-%s-%.4s", _model, anchorHex);

    LT_IM(CFG, "Identity: deviceId=%s apSSID=%s", _deviceId, _apSSID);

    if (!_load()) {
        LT_IM(CFG, "Identity: no valid keys, generating new ones");
        _generateKeys();
        if (!_save()) {
            LT_EM(CFG, "Identity: failed to save keys!");
        }
    }
}

bool DeviceIdentity::secretHex(String& out) const {
    if (!_hasSecret) return false;
    char hex[KEY_LEN * 2 + 1];
    _hex(_secret, KEY_LEN, hex);
    out = hex;
    return true;
}

bool DeviceIdentity::controlKeyHex(String& out) const {
    if (!_hasControlKey) return false;
    char hex[KEY_LEN * 2 + 1];
    _hex(_controlKey, KEY_LEN, hex);
    out = hex;
    return true;
}

bool DeviceIdentity::setSecretHex(const char* hex) {
    size_t n = 0;
    if (!_unhex(hex, _secret, KEY_LEN, &n) || n != KEY_LEN) return false;
    _hasSecret = true;
    return _save();
}

bool DeviceIdentity::setControlKeyHex(const char* hex) {
    size_t n = 0;
    if (!_unhex(hex, _controlKey, KEY_LEN, &n) || n != KEY_LEN) return false;
    _hasControlKey = true;
    return _save();
}

void DeviceIdentity::reset() {
    _hasSecret = false;
    _hasControlKey = false;
    memset(_secret, 0, sizeof(_secret));
    memset(_controlKey, 0, sizeof(_controlKey));
    compat::kvDel(_kvKey());
}

bool DeviceIdentity::_load() {
    uint8_t blob[2 * BLOB_REC_LEN];
    size_t storedLen = 0;
    int err = compat::kvGet(_kvKey(), blob, sizeof(blob), &storedLen);
    if (err != 0 || storedLen < sizeof(blob)) {
        return false; // chưa có / hỏng / blob của thiết bị khác
    }

    uint8_t key[KEY_LEN];
    _deriveKey(_anchor, _anchorLen, key);

    if (!_decrypt(key, blob, _secret)) return false;
    if (!_decrypt(key, blob + BLOB_REC_LEN, _controlKey)) return false;

    _hasSecret = true;
    _hasControlKey = true;
    return true;
}

bool DeviceIdentity::_save() const {
    uint8_t blob[2 * BLOB_REC_LEN];

    uint8_t key[KEY_LEN];
    _deriveKey(_anchor, _anchorLen, key);

    if (!_encrypt(key, _secret, KEY_LEN, blob)) return false;
    if (!_encrypt(key, _controlKey, KEY_LEN, blob + BLOB_REC_LEN)) return false;

    return compat::kvSet(_kvKey(), blob, sizeof(blob)) == 0;
}

void DeviceIdentity::_generateKeys() {
    chip::randomBytes(_secret, KEY_LEN);
    chip::randomBytes(_controlKey, KEY_LEN);
    _hasSecret = true;
    _hasControlKey = true;
}

void DeviceIdentity::_deriveKey(const uint8_t* anchor, size_t len, uint8_t key[KEY_LEN]) {
    const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    mbedtls_md(md, anchor, len, key);
}

bool DeviceIdentity::_encrypt(const uint8_t key[KEY_LEN], const uint8_t* plain, size_t plainLen,
                              uint8_t out[BLOB_REC_LEN]) {
    uint8_t iv[16];
    chip::randomBytes(iv, sizeof(iv));
    memcpy(out, iv, 16);

    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, KEY_LEN * 8) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    int r = mbedtls_gcm_crypt_and_tag(&ctx, MBEDTLS_GCM_ENCRYPT, plainLen,
        iv, sizeof(iv), nullptr, 0, plain, out + 16, 16, out + 48);
    mbedtls_gcm_free(&ctx);
    return r == 0;
}

bool DeviceIdentity::_decrypt(const uint8_t key[KEY_LEN], const uint8_t blob[BLOB_REC_LEN],
                              uint8_t out[KEY_LEN]) {
    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, KEY_LEN * 8) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    int r = mbedtls_gcm_auth_decrypt(&ctx, KEY_LEN, blob, 16, nullptr, 0,
        blob + 48, 16, blob + 16, out);
    mbedtls_gcm_free(&ctx);
    return r == 0;
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
