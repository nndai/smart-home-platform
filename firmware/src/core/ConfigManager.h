#pragma once

#include <Arduino.h>
#include <cstring>
#include <Config.h>
#include "compat/log.h"
#include "compat/kv.h"
#include "core/Crypto.h"

enum class ConnMode : uint8_t { AP_WS = 0, STA_MQTT, DEBUG_WS };
enum class RelayStartMode : uint8_t { OFF = 0, ON, LAST };

// ── Phần config CHUNG mọi thiết bị (core) ──
// Field riêng của từng profile: kế thừa struct này (vd profiles/pump/PumpConfig.h).
struct DeviceConfig {
    // ── Connection mode ──
    ConnMode connMode = ConnMode::DEBUG_WS;

    // ── WiFi STA ──
    char wifiSSID[32] = "";
    char wifiPass[64] = "";

    // ── WiFi DEBUG ──
    char debugSSID[32] = DEFAULT_DEBUG_SSID;
    char debugPass[64] = DEFAULT_DEBUG_PASSWORD;
    uint8_t debugIp[4] = DEFAULT_DEBUG_IP;
    uint8_t debugGateway[4] = DEFAULT_DEBUG_GATEWAY;
    uint8_t debugNetmask[4] = DEFAULT_DEBUG_NETMASK;

    // ── MQTT ──
    char mqttServer[64] = "";
    uint16_t mqttPort = DEFAULT_MQTT_PORT;
    char mqttUser[32] = "";

    // ── MQTT pass mã hóa ──
    // key = SHA-256(deviceId); layout blob = IV(16)+cipher(32)+tag(16)
    bool mqttPassEncValid = false;
    uint8_t mqttPassEnc[64] = {0};

    // ── Sys log file ──
    bool sysLogFileEnabled = true;
    uint8_t sysLogFileLevel = LT_LEVEL_DEBUG;
};

// ── Quản lý config dạng blob KV (một key "app_cfg") ──
// T = kiểu config của profile (PumpConfig, SwitchConfig...) — blob có kích thước
// tùy profile, load tự xử lý nếu bản cũ nhỏ hơn (mặc định giữ, field mới dùng default).
template <typename T = DeviceConfig>
class ConfigManagerT {
public:
    // Seed derive key mã hoá mqttPass (deviceId + FW_SECRET build secret) — gọi
    // sau g_identity.begin(), TRƯỚC load() (xem main.cpp).
    void setEncSeed(const char* seed) { _encSeed = seed; }

    bool load() { return load(_config); }
    bool load(T& cfg) {
        cfg = T();
        size_t storedLen = 0;
        uint8_t buf[sizeof(T)];
        KvError err = compat::kvGet(kvKey(), buf, sizeof(buf), &storedLen);
        if (err == KvError::BufTooShort) {
            // Blob mới hơn firmware hiện tại (storedLen > sizeof(T)) → đọc đầy đủ rồi cắt bớt.
            uint8_t* tmp = (uint8_t*)malloc(storedLen);
            if (!tmp) return false;
            size_t got = 0;
            err = compat::kvGet(kvKey(), tmp, storedLen, &got);
            if (err == KvError::Ok) {
                memcpy(&cfg, tmp, sizeof(T));
            }
            free(tmp);
            if (err != KvError::Ok) return false;
            _decryptPass(cfg);
            return true;
        }
        if (err != KvError::Ok) return false;  // NotExist (hoặc lỗi khác) → giữ defaults
        size_t copyLen = (storedLen < sizeof(T)) ? storedLen : sizeof(T);
        memcpy(&cfg, buf, copyLen);
        _decryptPass(cfg);
        return true;
    }

    bool save() { return save(_config); }
    bool save(const T& cfg) {
        T tmp = cfg;
        // Mã hoá mqttPass (bản rõ RAM) trước khi ghi flash — flash chỉ chứa bản mã.
        if (_encSeed) {
            if (_plainMqttPass[0] != '\0') {
                uint8_t key[32];
                if (crypto::cfgKeyFromSeed(_encSeed, key) && crypto::cfgEncryptPass(key, _plainMqttPass, tmp.mqttPassEnc)) {
                    tmp.mqttPassEncValid = true;
                }
            } else {
                // Pass bị xoá → phải xoá luôn bản mã cũ, không thì load lại trả pass cũ.
                tmp.mqttPassEncValid = false;
                memset(tmp.mqttPassEnc, 0, sizeof(tmp.mqttPassEnc));
            }
        }
        return compat::kvSet(kvKey(), &tmp, sizeof(tmp)) == KvError::Ok;
    }

    bool reset() {
        _config = T();
        memset(_plainMqttPass, 0, sizeof(_plainMqttPass));
        return compat::kvDel(kvKey()) == KvError::Ok;
    }

    T& get() {
        return _config;
    }

    // ── MQTT pass bản rõ — chỉ sống trong RAM, không bao giờ xuống flash ──
    const char* passPlain() const { return _plainMqttPass; }

    void setPassPlain(const char* p) {
        if (!p) {
            _plainMqttPass[0] = '\0';
        } else {
            strlcpy(_plainMqttPass, p, sizeof(_plainMqttPass));
        }
    }

    void print() {
        LT_IM(CFG, "── Config ──");
        LT_IM(CFG, "  WiFi: %s", _config.wifiSSID);
        LT_IM(CFG, "  MQTT: %s:%d user=%s secPass=%s", _config.mqttServer, _config.mqttPort,
            _config.mqttUser[0] ? _config.mqttUser : "(per-device)",
            _config.mqttPassEncValid ? "enc" : "none");
        LT_IM(CFG, "  Log: logFile=%s level=%d",
            _config.sysLogFileEnabled ? "ON" : "OFF", _config.sysLogFileLevel);
        LT_IM(CFG, "  ConnMode: %s", getConnModeString(_config.connMode));
        if (_config.connMode == ConnMode::DEBUG_WS) {
            LT_IM(CFG, "  DebugSSID: %s", _config.debugSSID);
            LT_IM(CFG, "  DebugIP: %d.%d.%d.%d gw=%d.%d.%d.%d mask=%d.%d.%d.%d",
                _config.debugIp[0], _config.debugIp[1], _config.debugIp[2], _config.debugIp[3],
                _config.debugGateway[0], _config.debugGateway[1], _config.debugGateway[2], _config.debugGateway[3],
                _config.debugNetmask[0], _config.debugNetmask[1], _config.debugNetmask[2], _config.debugNetmask[3]);
        }
    }

    static const char* getConnModeString(ConnMode mode) {
        switch (mode) {
        case ConnMode::AP_WS:
            return "AP_WS";
        case ConnMode::STA_MQTT:
            return "STA_MQTT";
        case ConnMode::DEBUG_WS:
            return "DEBUG_WS";
        default:
            return "UNKNOWN";
        }
    }

private:
    static const char* kvKey() { return "app_cfg"; }

    // Giải mã mqttPass từ blob đã lưu vào bản rõ RAM.
    void _decryptPass(T& cfg) {
        if (!cfg.mqttPassEncValid) {
            return;
        }
        uint8_t key[32];
        char plain[32];
        if (crypto::cfgKeyFromSeed(_encSeed, key) && crypto::cfgDecryptPass(key, cfg.mqttPassEnc, plain)) {
            strlcpy(_plainMqttPass, plain, sizeof(_plainMqttPass));
        } else {
            // Không giải mã được (blob hỏng / key lệch) → bỏ pass, fallback per-device.
            _plainMqttPass[0] = '\0';
            cfg.mqttPassEncValid = false;
            memset(cfg.mqttPassEnc, 0, sizeof(cfg.mqttPassEnc));
        }
    }

    T _config;
    const char* _encSeed = nullptr;
    char _plainMqttPass[32] = "";
};

using ConfigManager = ConfigManagerT<DeviceConfig>;
