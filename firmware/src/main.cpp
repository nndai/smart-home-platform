#include "compat/log.h"
#include "compat/task.h"
#include "compat/wdt.h"
#include "compat/wifi.h"
#include <Arduino.h>
#include <Config.h>
#include <NTPClient.h>
#include <WiFiUdp.h>

#include "chip/io.h"
#include "chip/softap.h"
#include "compat/log_capture.h"
#include "compat/pm.h"
#include "core/BuildInfo.h"
#include "core/CommandHandler.h"
#include "core/ConfigManager.h"
#include "core/DeviceIdentity.h"
#include "core/MqttClient.h"
#include "core/OTAManager.h"
#include "core/WebSocketServer.h"
#include "core/log/LogManager.h"
#include "profiles/registry.h"

// ── Global objects ──
ConfigManagerT<ProfileConfig> configManager;
DeviceIdentity identity;
DeviceDriver* driver = nullptr;
MqttClient mqttClient;
WebSocketServer wsServer(WEBSOCKET_PORT);
LogManager logManager;
WiFiUDP ntpUdp;
NTPClient ntpClient(ntpUdp, TZ_OFFSET_SEC);
OTAManager otaManager;
CommandHandlerT<ProfileConfig> commandHandler;
template class CommandHandlerT<ProfileConfig>;
ConnMode g_connMode = ConnMode::AP_WS;


// ── Forward declarations ──
uint32_t taskWifiConnect_cb();
uint32_t taskWsLoop_cb();
uint32_t taskMqttLoop_cb();
uint32_t taskNtpUpdate_cb();
uint32_t driverTask_cb();
uint32_t taskWdtFeed_cb();
uint32_t taskStreamSender_cb();

static void setupAP_WS(ProfileConfig& cfg);
static void setupSTA_MQTT(ProfileConfig& cfg);
static void setupDEBUG_WS(ProfileConfig& cfg);
static void onMqttMessage(const String& topic, const String& payload);
static void onWsMessage(const String& clientId, const String& message);
static void onWsBinary(const String& clientId, const uint8_t* data, size_t len);
static void sendResponse(const String& target, const String& json);
static String mqttBaseTopic();

// ── Setup ──
void setup() {
    delay(10);
    logCaptureInit();
    LT_IM(SYS, "=== Smart Home Controller ===");
    LT_IM(SYS, "FW Build: %s", buildStr());
    LT_IM(SYS, "Boot Reason: %s", chip::systemResetReason().c_str());
    LT_IM(SYS, "Free Heap: %u bytes", ESP.getFreeHeap());

    // Watchdog
    if (compat::wdtEnable(WDT_TIMEOUT_MS)) {
        sysTaskCreate("wdtFeed", WDT_FEED_INTERVAL_MS, taskWdtFeed_cb,
            TASK_WDT_STACK, TASK_WDT_PRIO);
        LT_IM(SYS, "Watchdog enabled, 15s timeout");
    }
    else {
        LT_IM(SYS, "Watchdog not supported");
    }

    // LITTLEFS.format();
    if (!LITTLEFS.begin()) {
        LT_IM(SYS, "LittleFS mount failed, formatting...");
        LITTLEFS.format();
        if (!LITTLEFS.begin()) {
            LT_IM(SYS, "LittleFS still failed!");
        }
    }

    identity.begin(profileName());

    // Seed mã hóa = deviceId + FW_SECRET (FW_SECRET từ .env qua build flag,
    // xem scripts/build_env.py). Dùng chung cho controlKey (identity blob) và
    // mqttPass (config). FW_SECRET rỗng → chỉ deviceId (compat build cũ).
    String encSeed = String(identity.deviceId()) + FW_SECRET;
    identity.setEncSeed(encSeed.c_str());
    configManager.setEncSeed(encSeed.c_str());

    if (!configManager.load(configManager.get())) {
        LT_IM(CFG, "No config found, using defaults");
    }
    ProfileConfig& cfg = configManager.get();

    logManager.begin();
    // Ghi file khi config bật (logFile=ON), bất kể connMode — user muốn
    // forward toàn bộ log sang file kể cả khi debug qua WS.
    logManager.setSysLogFileEnabled(cfg.sysLogFileEnabled);
    logManager.setSysLogFileLevel(cfg.sysLogFileLevel);
    // Ghi các dòng log lưu tạm (trước LITTLEFS init) vào file và xóa cấp
    // phát động; từ đây log đẩy thẳng vào LogManager.
    logCaptureFlushFile(&logManager);

    logManager.setLogCallback([](const String& line) {
        JsonDocument logJson;
        logJson["cmd"] = "log";
        logJson["msg"] = line;
        String json;
        serializeJson(logJson, json);
        if (g_connMode == ConnMode::DEBUG_WS || g_connMode == ConnMode::AP_WS) {
            wsServer.broadcast(json);
        }
        if (logManager.isMqttLogEnabled()) {
            mqttClient.publish(mqttBaseTopic() + "/log", json);
        }
        });

    configManager.print();

    // Driver thiết bị (theo profile) — wiring + calib + relayStartMode nằm trong
    // driver
    driver = createDriver();
    driver->setServices(
        { &logManager, [&]() { return configManager.save(); },
        [&]() {
            configManager.reset();
            identity.reset();
        },
        [](const String& json) {
            if (g_connMode == ConnMode::STA_MQTT) {
                mqttClient.publish(mqttBaseTopic() + "/up", json);
            }
            else {
                wsServer.broadcast(json);
            }
        },
        []() {
            return g_connMode == ConnMode::STA_MQTT && mqttClient.isConnected();
        },
        [](const String& topic, const String& payload) {
            if (g_connMode != ConnMode::STA_MQTT)
                return false;
            return mqttClient.publish(topic, payload);
        },
        [](const String& topic) {
            mqttClient.subscribeExtra(topic); 
        },
        [&]() { 
            commandHandler.publishStatusToUp(); 
        }, 
        identity.deviceId(),
        &identity
        });

    driver->begin(configManager.get(), [&]() { return configManager.save(); });

    otaManager.begin();

    commandHandler.begin(&configManager, &logManager, &otaManager, &identity, profileName());
    commandHandler.setDriver(driver);
    commandHandler.setResponseCallback(sendResponse);

    compat::pmDisableUnusedClocks();
    // ln_pm_sleep_mode_set(LIGHT_SLEEP);

    // Connection-specific setup
    // Device luôn tự sinh secret+controlKey lúc boot đầu (isProvisioned()=true),
    // nên AP pairing portal myhome-<model>-XXXX được điều khiển bằng connMode
    // default=AP_WS (device mới / sau factoryReset). STA_MQTT chỉ khi đã pair
    // thành công.
    g_connMode = cfg.connMode;
    switch (g_connMode) {
    case ConnMode::AP_WS:
        setupAP_WS(cfg);
        sysTaskCreate("ws", 50, taskWsLoop_cb, TASK_NETWORK_STACK, TASK_NETWORK_PRIO);
        break;
    case ConnMode::STA_MQTT:
        setupSTA_MQTT(cfg);
        sysTaskCreate("wifiConn", 100, taskWifiConnect_cb, TASK_NETWORK_STACK, TASK_NETWORK_PRIO);
        break;
    case ConnMode::DEBUG_WS:
        setupDEBUG_WS(cfg);
        sysTaskCreate("wifiConn", 100, taskWifiConnect_cb, TASK_NETWORK_STACK, TASK_NETWORK_PRIO);
        break;
    }

    sysTaskCreate("driver", 20, driverTask_cb, TASK_SENSOR_STACK, TASK_SENSOR_PRIO);
    sysTaskCreate("stream", 2000, taskStreamSender_cb, TASK_STREAM_STACK, TASK_STREAM_PRIO);

    LT_IM(SYS, "System ready!");
}

void loop() { 
    compatRunSchedulerStep(); 
}

// ── Connection setup functions ──

void setupWiFiSTA(ProfileConfig& cfg) {
    WiFi.mode(WIFI_STA);
    WiFi.setHostname("iphone");
    if (cfg.connMode == ConnMode::STA_MQTT) {
        WiFi.config(IPAddress(0, 0, 0, 0), IPAddress(0, 0, 0, 0),
            IPAddress(0, 0, 0, 0));
        WiFi.begin(cfg.wifiSSID, cfg.wifiPass);
    }
    else if (cfg.connMode == ConnMode::DEBUG_WS) {
        WiFi.config(IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2],
            cfg.debugIp[3]),
            IPAddress(cfg.debugGateway[0], cfg.debugGateway[1],
                cfg.debugGateway[2], cfg.debugGateway[3]),
            IPAddress(cfg.debugNetmask[0], cfg.debugNetmask[1],
                cfg.debugNetmask[2], cfg.debugNetmask[3]));
        WiFi.begin(cfg.debugSSID, cfg.debugPass);
    }
}

static void setupAP_WS(ProfileConfig& cfg) {
    const char* apPass = DEFAULT_AP_PASSWORD; // WPA2 mặc định
    LT_IM(NET, "AP mode: SSID=%s %s", identity.apSSID(),
        apPass ? "(WPA2)" : "(open)");

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(
        IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
        IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
        IPAddress(cfg.debugNetmask[0], cfg.debugNetmask[1], cfg.debugNetmask[2],
            cfg.debugNetmask[3]));

    // WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1),
    // IPAddress(255, 255, 255, 0));

    chip::softApStart(identity.apSSID(), apPass, 1);

    chip::reclaimRelayGpio();

    wsServer.begin();
    wsServer.setCallback(onWsMessage);
    wsServer.setBinaryCallback(onWsBinary);
}

static void setupSTA_MQTT(ProfileConfig& cfg) {
    LT_IM(NET, "STA+MQTT mode: connecting to %s", cfg.wifiSSID);
    setupWiFiSTA(cfg);

    chip::reclaimRelayGpio();

    String clientId = String("device-") + identity.deviceId();
    if (cfg.mqttUser[0] == '\0') {
        LT_EM(NET, "MQTT: no credential (device not paired) — MQTT disabled");
        return;
    }
    mqttClient.begin(cfg.mqttServer, cfg.mqttPort, cfg.mqttUser,
        configManager.passPlain(), clientId.c_str(),
        mqttBaseTopic().c_str());
    mqttClient.setCallback(onMqttMessage);
}

static void setupDEBUG_WS(ProfileConfig& cfg) {
    LT_IM(NET, "Debug mode: connecting to %s", cfg.debugSSID);
    LT_IM(NET, "Debug mode: IP %d.%d.%d.%d", cfg.debugIp[0], cfg.debugIp[1],
        cfg.debugIp[2], cfg.debugIp[3]);

    setupWiFiSTA(cfg);

    chip::reclaimRelayGpio();
}

// ── Task: WiFi Connect ──
uint32_t taskWifiConnect_cb() {
    if (WiFi.status() != WL_CONNECTED) {
        return 100;
    }

    switch (g_connMode) {
    case ConnMode::DEBUG_WS:
        sysTaskCreate("ws", 50, taskWsLoop_cb, TASK_NETWORK_STACK, TASK_NETWORK_PRIO);
        break;
    case ConnMode::STA_MQTT:
        sysTaskCreate("mqtt", 50, taskMqttLoop_cb, TASK_NETWORK_STACK, TASK_NETWORK_PRIO);
        sysTaskCreate("ntp", 5000, taskNtpUpdate_cb, TASK_NTP_STACK, TASK_NTP_PRIO);
        break;
    default:
        break;
    }

    WiFi.setSleep(true);
    return TASK_DELETE;
}

// ── Task: WebSocket Loop ──
uint32_t taskWsLoop_cb() {
    static bool started = false;
    if (!started) {
        wsServer.begin();
        wsServer.setCallback(onWsMessage);
        wsServer.setBinaryCallback(onWsBinary);
        LT_IM(NET, "WebSocket server started");
        started = true;
    }

    compat::wdtFeed();
    switch (g_connMode) {
    case ConnMode::DEBUG_WS:
    case ConnMode::AP_WS:
        wsServer.handle();
        break;
    }
    return otaManager.isRunning() ? 5 : 50;
}

// ── Task: MQTT Loop ──
uint32_t taskMqttLoop_cb() {
    static bool logLostConnection = true;

    compat::wdtFeed();
    bool connected = mqttClient.loop();
    if (!connected && !logLostConnection) {
        LT_E("MQTT connection lost. Attempting to reconnect...");
        logLostConnection = true;
    }
    else if (connected && logLostConnection) {
        LT_I("MQTT reconnected");
        logLostConnection = false;
    }

    return otaManager.isRunning() ? 10 : 50;
}

// ── Task: NTP Update ──
uint32_t taskNtpUpdate_cb() {
    static bool started = false;
    static bool isLogTime = false;

    if (!started) {
        ntpClient.begin();
        started = true;
    }

    ntpClient.update();

    if (ntpClient.isTimeSet()) {
        logManager.setTime(ntpClient.getEpochTime());
        if (!isLogTime) {
            LT_I("NTP time set: %s", ntpClient.getFormattedTime().c_str());
            isLogTime = true;
        }
    }
    return ntpClient.isTimeSet() ? 61000 : 5000;
}

// ── Watchdog Feeder Task ──
uint32_t taskWdtFeed_cb() {
    if (ESP.getFreeHeap() < HEAP_CRITICAL_BYTES) {
        LT_IM(SYS, "Heap critically low (%u bytes), restarting!",
            ESP.getFreeHeap());
        ESP.restart();
        while (1) {
        }
    }
    compat::wdtFeed();
    chip::scanPumpDoneEvent();
    return WDT_FEED_INTERVAL_MS;
}

// ── Driver Task ──
uint32_t driverTask_cb() {
    driver->loop(millis());
    return 20;
}

// ── Stream Sender Task ──
uint32_t taskStreamSender_cb() {
    static uint32_t lastStatusUp = 0;

    commandHandler.sendStream(CommandHandlerT<ProfileConfig>::STREAM_STATUS);
    commandHandler.sendStream(CommandHandlerT<ProfileConfig>::STREAM_SYSINFO);

    uint32_t nowMs = millis();
    if (!commandHandler.isStreamActive(
        CommandHandlerT<ProfileConfig>::STREAM_STATUS)) {
        if (nowMs - lastStatusUp >= 60000) {
            lastStatusUp = nowMs;
            commandHandler.publishStatusToUp();
        }
    }
    return 2000;
}


// ── MQTT topic chuẩn: devices/{deviceId}/cmd|up|log ──
static String mqttBaseTopic() {
    return String("devices/") + identity.deviceId();
}

// ── Callbacks ──
static void onMqttMessage(const String& topic, const String& payload) {
    // Topic của chính mình (devices/{id}/cmd|otachunk|down) → lệnh cho
    // CommandHandler
    if (topic.startsWith(mqttBaseTopic() + "/")) {
        commandHandler.handleCommand("mqtt", payload);
        return;
    }

    // Topic do driver đăng ký (vd: devices/{targetId}/up của Remote Switch)
    // → đẩy thẳng cho driver; topic lạ khác bị bỏ qua.
    if (mqttClient.isExtraTopic(topic)) {
        if (driver) {
            JsonDocument doc;
            deserializeJson(doc, payload);
            driver->handleTargetStatus(doc);
        }
    }
}

static void onWsMessage(const String& clientId, const String& message) {
    (void)clientId;
    // LT_IM(WS, "Received message: %s", message.c_str());
    commandHandler.handleCommand("ws", message);
}

static void onWsBinary(const String& clientId, const uint8_t* data,
    size_t len) {
    if (otaManager.isRunning()) {
        if (!otaManager.writeChunk(data, len)) {
            otaManager.writeError();
        }
    }
}

static void sendResponse(const String& target, const String& json) {

    if (target == "mqtt") {
        mqttClient.publish(mqttBaseTopic() + "/up", json);
    }
    if (target == "ws") {
        wsServer.broadcast(json);
    }
}
