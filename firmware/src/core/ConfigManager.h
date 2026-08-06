#pragma once

#include <Arduino.h>
#include <cstring>
#include <Config.h>
#include "compat/log.h"
#include "compat/kv.h"

enum class ConnMode : uint8_t { AP_WS = 0, STA_MQTT, DEBUG_WS };
enum class RelayStartMode : uint8_t { OFF = 0, ON, LAST };

// ── Phần config CHUNG mọi thiết bị (core) ──
// Field riêng của từng profile: kế thừa struct này (vd profiles/pump/PumpConfig.h).
struct DeviceConfig {
    // ── Connection mode ──
    ConnMode connMode = ConnMode::AP_WS;

    // ── WiFi STA ──
    char wifiSSID[32] = "";
    char wifiPass[64] = "";

    // ── WiFi AP ──
    char apSSID[32] = DEFAULT_AP_SSID;
    char apPass[64] = DEFAULT_AP_PASSWORD;

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
    char mqttPass[32] = "";
    char mqttTopic[64] = DEFAULT_MQTT_TOPIC;

    // ── Relay startup mode (OFF=0 / ON=1 / LAST=2) ──
    RelayStartMode relayStartMode = RelayStartMode::OFF;

    // ── Sys log file ──
    bool sysLogFileEnabled = true;
    uint8_t sysLogFileLevel = LT_LEVEL_DEBUG;

    // Chỉ append field mới ở cuối struct, không chèn giữa.
};

// ── Quản lý config dạng blob KV (một key "app_cfg") ──
// T = kiểu config của profile (PumpConfig, SwitchConfig...) — blob có kích thước
// tùy profile, load tự xử lý nếu bản cũ nhỏ hơn (mặc định giữ, field mới dùng default).
template <typename T = DeviceConfig>
class ConfigManagerT {
public:
    bool load() { return load(_config); }
    bool load(T& cfg) {
        size_t storedLen = 0;
        uint8_t buf[sizeof(T)];
        int err = compat::kvGet(kvKey(), buf, sizeof(buf), &storedLen);
        if (err == 1) {
            cfg = T();
            return false;
        }
        if (err == 3) {
            uint8_t* tmp = (uint8_t*)malloc(storedLen);
            if (!tmp) {
                cfg = T();
                return false;
            }
            size_t got = 0;
            int err2 = compat::kvGet(kvKey(), tmp, storedLen, &got);
            if (err2 != 0) {
                free(tmp);
                cfg = T();
                return false;
            }
            cfg = T();
            memcpy(&cfg, tmp, sizeof(T));
            free(tmp);
            return true;
        }
        if (err != 0) {
            cfg = T();
            return false;
        }
        cfg = T();
        size_t copyLen = (storedLen < sizeof(T)) ? storedLen : sizeof(T);
        memcpy(&cfg, buf, copyLen);
        return true;
    }

    bool save() { return save(_config); }
    bool save(const T& cfg) {
        return compat::kvSet(kvKey(), &cfg, sizeof(cfg)) == 0;
    }

    bool reset() {
        _config = T();
        compat::kvDel(kvKey());
        return true;
    }

    T& get() {
        return _config;
    }

    void print() {
        LT_IM(CFG, "── Config ──");
        LT_IM(CFG, "  WiFi: %s", _config.wifiSSID);
        LT_IM(CFG, "  MQTT: %s:%d", _config.mqttServer, _config.mqttPort);
        LT_IM(CFG, "  Topic: %s", _config.mqttTopic);
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
    T _config;
};

using ConfigManager = ConfigManagerT<DeviceConfig>;
