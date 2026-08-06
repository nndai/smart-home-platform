#pragma once

#include <Arduino.h>
#include <cstring>
#include <Config.h>

enum class ConnMode : uint8_t { AP_WS = 0, STA_MQTT, DEBUG_WS };
enum class RelayStartMode : uint8_t { OFF = 0, ON, LAST };

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

    // ── Pump protection thresholds (mA) ──
    uint16_t threshOff = DEFAULT_THRESH_OFF;
    uint16_t threshNoWater = DEFAULT_THRESH_NO_WATER;
    uint16_t threshRunning = DEFAULT_THRESH_RUNNING;
    uint16_t threshOverload = DEFAULT_THRESH_OVERLOAD;

    // ── Pump protection timeouts (ms) ──
    uint16_t dryTimeout = DEFAULT_NO_WATER_TIMEOUT;
    uint16_t overloadTimeout = DEFAULT_OVERLOAD_TIMEOUT;

    // ── Device mode ──
    bool pumpMode = DEFAULT_PUMP_MODE;

    // ── Relay startup mode (OFF=0 / ON=1 / LAST=2) ──
    RelayStartMode relayStartMode = RelayStartMode::OFF;

    // ── BL0937 calibration coefficients (NAN = use HW defaults) ──
    double cCal = NAN;   // current coefficient
    double vCal = NAN;   // voltage coefficient
    double pCal = NAN;   // power coefficient

    // ── Sys log file ──
    bool sysLogFileEnabled = true;
    uint8_t sysLogFileLevel = LT_LEVEL_DEBUG;

    // Chỉ append field mới ở cuối struct, không chèn giữa.
};

class ConfigManager {
public:
    ConfigManager();
    bool load(DeviceConfig& cfg);
    bool save(const DeviceConfig& cfg);
    bool reset();
    DeviceConfig& get();
    void print();

    static const char* getConnModeString(ConnMode mode);

private:
    DeviceConfig _config;
    static constexpr const char* KV_KEY = "app_cfg";
};
