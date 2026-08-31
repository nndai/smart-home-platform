#pragma once

#include <Arduino.h>

// ── Danh tính thiết bị (dùng chung, không #ifdef) ──
//   deviceId    = "dev-" + hex(SHA256(anchor))[:12] — công khai, in lên nhãn/QR
//   apSSID      = "myhome-<model>-" + hex(anchor)[:4] — app nhận biết loại thiết bị
//   controlKey  = 32B ngẫu nhiên, sinh lần boot đầu (app ghi đè lúc pair),
//       lưu MÃ HÓA AES-GCM trong KV "ident": key = SHA-256(deviceId + FW_SECRET).
//   → dump flash không lấy được controlKey; nạp sang chip khác: deviceId khác →
//     key khác → giải mã fail → coi như chưa provision (clone vô dụng).
class DeviceIdentity {
public:
    // Gọi sớm trong setup(): đọc anchor → deviceId/apSSID.
    // model = tên profile (pump/switch/...), dùng làm tiền tố AP SSID.
    void begin(const char* model);

    const char* deviceId() const { return _deviceId; }
    const char* apSSID() const { return _apSSID; }

    // Có controlKey hợp lệ thì coi là đã provision.
    bool isProvisioned() const { return _hasControlKey; }

    // Seed mã hóa = deviceId + FW_SECRET (cùng seed với mqttPass, xem
    // ConfigManager::setEncSeed). Bắt buộc gọi sau begin() TRƯỚC khi load/save:
    // derive key → load blob mã hóa; chưa có / giải mã fail (clone, đổi secret)
    // → sinh controlKey mới và lưu.
    void setEncSeed(const char* seed);

    // Lấy con trỏ trực tiếp tới 32 bytes controlKey trong RAM (không cấp phát)
    const uint8_t* controlKey() const { return _hasControlKey ? _controlKey : nullptr; }
    bool getControlKey(uint8_t out[32]) const {
        if (!_hasControlKey || !out) return false;
        memcpy(out, _controlKey, KEY_LEN);
        return true;
    }

    // Ghi đè controlKey dạng raw byte 32B
    bool setControlKey(const uint8_t key[32]);

    // Giải mã về RAM (không bao giờ lưu plaintext xuống flash).
    bool controlKeyHex(String& out) const;

    // Factory inject: ghi đè controlKey (hex 64 ký tự). Trả false nếu hex sai.
    bool setControlKeyHex(const char* hex);

    // Xóa KV → thiết bị trở về unprovisioned (re-pair, xoay khóa).
    void reset();

private:
    static const uint8_t KEY_LEN = 32;
    static const uint8_t BLOB_LEN = 64; // iv(16) + ct(32) + tag(16)

    uint8_t _anchor[16];
    size_t _anchorLen = 0;
    char _deviceId[17]; // "dev-" + 12 hex + NUL
    char _apSSID[33];   // "myhome-<model>-XXXX" + NUL (SSID max 32)
    char _model[16];    // tên profile: pump/switch/...
    uint8_t _encKey[32];
    bool _hasEncKey = false;
    uint8_t _controlKey[KEY_LEN];
    bool _hasControlKey = false;

    bool _load();        // đọc KV + giải mã
    bool _save() const;  // mã hóa + ghi KV
    void _generateKeys();

    static const char* _kvKey() { return "ident"; }
};