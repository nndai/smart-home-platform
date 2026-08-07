#pragma once

#include <Arduino.h>

// ── Danh tính thiết bị (dùng chung, không #ifdef) ──
//   deviceId    = "dev-" + hex(anchor)[:12]  — công khai, in lên nhãn/QR
//   apSSID      = "myhome-<model>-" + hex(anchor)[:4] — app nhận biết loại thiết bị
//   deviceSecret/controlKey = 32B ngẫu nhiên, sinh lần boot đầu, lưu MÃ HÓA
//       AES-GCM(key = SHA256(anchor)) trong KV "ident".
//   → dump flash không lấy được secret; nạp sang chip khác: anchor khác →
//     key khác → giải mã fail → coi như chưa provision (clone vô dụng).
// Phase này: sinh + lưu + đọc. Phase sau: dùng secret cho MQTT, controlKey
// cho envelope seq/ts/hmac.
class DeviceIdentity {
public:
    // Gọi sớm trong setup(): đọc anchor → deviceId/apSSID; load blob mã hóa;
    // nếu chưa có (hoặc giải mã fail = clone) → sinh khóa mới và lưu.
    // model = tên profile (pump/switch/...), dùng làm tiền tố AP SSID.
    void begin(const char* model);

    const char* deviceId() const { return _deviceId; }
    const char* apSSID() const { return _apSSID; }

    // Có đủ secret + controlKey hợp lệ thì coi là đã provision.
    bool isProvisioned() const { return _hasSecret && _hasControlKey; }

    // Giải mã về RAM (không bao giờ lưu plaintext xuống flash).
    bool secretHex(String& out) const;
    bool controlKeyHex(String& out) const;

    // Factory inject: ghi đè key (hex 64 ký tự). Trả false nếu hex sai.
    bool setSecretHex(const char* hex);
    bool setControlKeyHex(const char* hex);

    // Xóa KV → thiết bị trở về unprovisioned (re-pair, xoay khóa).
    void reset();

private:
    static const uint8_t KEY_LEN = 32;
    static const uint8_t BLOB_REC_LEN = 64; // iv(16) + ct(32) + tag(16)

    uint8_t _anchor[16];
    size_t _anchorLen = 0;
    char _deviceId[17]; // "dev-" + 12 hex + NUL
    char _apSSID[33];   // "myhome-<model>-XXXX" + NUL (SSID max 32)
    char _model[16];    // tên profile: pump/switch/...
    uint8_t _secret[KEY_LEN];
    uint8_t _controlKey[KEY_LEN];
    bool _hasSecret = false;
    bool _hasControlKey = false;

    bool _load();        // đọc KV + giải mã
    bool _save() const;  // mã hóa + ghi KV
    void _generateKeys();

    static void _deriveKey(const uint8_t* anchor, size_t len, uint8_t key[KEY_LEN]);
    static bool _encrypt(const uint8_t key[KEY_LEN], const uint8_t* plain, size_t plainLen, uint8_t out[BLOB_REC_LEN]);
    static bool _decrypt(const uint8_t key[KEY_LEN], const uint8_t blob[BLOB_REC_LEN], uint8_t out[KEY_LEN]);

    static void _hex(const uint8_t* data, size_t len, char* out);
    static bool _unhex(const char* hex, uint8_t* out, size_t maxLen, size_t* outLen);

    static const char* _kvKey() { return "ident"; }
};
