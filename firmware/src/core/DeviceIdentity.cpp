#include "core/DeviceIdentity.h"

#include "core/Crypto.h"
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
    crypto::sha256(_anchor, _anchorLen, anchorHash);

    char anchorHex[13];
    crypto::hexEncode(anchorHash, 6, anchorHex);

    _deviceId[0] = 'd'; _deviceId[1] = 'e'; _deviceId[2] = 'v'; _deviceId[3] = '-';
    memcpy(_deviceId + 4, anchorHex, 12);
    _deviceId[16] = '\0';

    snprintf_P(_apSSID, sizeof(_apSSID), PSTR("myhome-%s-%.4s"), _model, anchorHex);

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
    crypto::hexEncode(_controlKey, KEY_LEN, hex);
    out = hex;
    return true;
}

bool DeviceIdentity::setControlKey(const uint8_t key[32]) {
    if (!key) return false;
    memcpy(_controlKey, key, KEY_LEN);
    _hasControlKey = true;
    return _save();
}

bool DeviceIdentity::setControlKeyHex(const char* hex) {
    size_t n = 0;
    if (!crypto::hexDecode(hex, _controlKey, KEY_LEN, &n) || n != KEY_LEN) return false;
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

    if (!crypto::decryptKey(_encKey, blob, _controlKey)) return false;

    _hasControlKey = true;
    return true;
}

bool DeviceIdentity::_save() const {
    if (!_hasEncKey) return false;

    uint8_t blob[BLOB_LEN];
    if (!crypto::encryptKey(_encKey, _controlKey, blob)) return false;

    return compat::kvSet(_kvKey(), blob, sizeof(blob)) == KvError::Ok;
}

void DeviceIdentity::_generateKeys() {
    chip::randomBytes(_controlKey, KEY_LEN);
    _hasControlKey = true;
}