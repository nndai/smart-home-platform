#include <Arduino.h>
#include "compat/log.h"
#include "compat/task.h"
#include <Config.h>
#include "compat/wifi.h"
#include <WiFiUdp.h>
#include <NTPClient.h>
#include "compat/wdt.h"

#include "core/ConfigManager.h"
#include "core/DeviceIdentity.h"
#include "core/MqttClient.h"
#include "core/WebSocketServer.h"
#include "core/log/LogManager.h"
#include "core/OTAManager.h"
#include "core/CommandHandler.h"
#include "core/BuildInfo.h"
#include "profiles/registry.h"
#include "compat/pm.h"
#include "compat/log_capture.h"
#include "chip/softap.h"
#include "chip/io.h"



// ── Global objects ──
ConfigManagerT<ProfileConfig> configManager;
DeviceIdentity g_identity;
DeviceDriver* g_driver = nullptr;
MqttClient mqttClient;
WebSocketServer wsServer(WEBSOCKET_PORT);
LogManager logManager;
WiFiUDP ntpUdp;
NTPClient ntpClient(ntpUdp, TZ_OFFSET_SEC);
OTAManager otaManager;
CommandHandlerT<ProfileConfig> commandHandler;

// Seed mã hóa chung: deviceId + FW_SECRET (build secret từ .env, xem
// scripts/build_env.py) — dùng cho controlKey (identity blob) + mqttPass.
// Phải là global: ConfigManager/DeviceIdentity giữ con trỏ tới buffer này
// suốt runtime (save() gọi bất kỳ lúc nào qua setConfig/calibrate...).
static String g_encSeed;

template class CommandHandlerT<ProfileConfig>;


// ── Runtime connection mode ──
ConnMode g_connMode = ConnMode::AP_WS;

// ── MQTT topic chuẩn (phase 2): devices/{deviceId}/cmd|up|log ──
static String mqttBaseTopic() {
    return String("devices/") + g_identity.deviceId();
}

// ── Extra MQTT topics do driver đăng ký (vd: devices/{targetId}/up của Remote Switch) ──
// Core chỉ ghi nhớ + re-subscribe khi reconnect; nội dung topic là việc của driver.
static constexpr uint8_t MAX_EXTRA_MQTT_TOPICS = 8;
static String g_extraMqttTopics[MAX_EXTRA_MQTT_TOPICS];
static uint8_t g_extraMqttCount = 0;

static void subscribeMqttTopic(const String& topic) {
    if (topic.length() == 0) return;
    for (uint8_t i = 0; i < g_extraMqttCount; i++) {
        if (g_extraMqttTopics[i] == topic) return;
    }
    if (g_extraMqttCount < MAX_EXTRA_MQTT_TOPICS) {
        g_extraMqttTopics[g_extraMqttCount++] = topic;
    }
    if (g_connMode == ConnMode::STA_MQTT && mqttClient.isConnected()) {
        mqttClient.subscribe(topic);
    }
}

static void resubscribeMqttTopics() {
    for (uint8_t i = 0; i < g_extraMqttCount; i++) {
        mqttClient.subscribe(g_extraMqttTopics[i]);
    }
}

static bool isExtraMqttTopic(const String& topic) {
    for (uint8_t i = 0; i < g_extraMqttCount; i++) {
        if (g_extraMqttTopics[i] == topic) return true;
    }
    return false;
}

// ── Forward declarations ──
void taskWifiConnect(void* pvParams);
void taskWsLoop(void* pvParams);
void taskMqttLoop(void* pvParams);
void taskNtpUpdate(void* pvParams);
void driverTask(void* pvParams);
void taskWdtFeed(void* pvParams);
void taskStreamSender(void* pvParams);

static void setupAP_WS(ProfileConfig& cfg);
static void setupSTA_MQTT(ProfileConfig& cfg);
static void setupDEBUG_WS(ProfileConfig& cfg);
static void onMqttMessage(const String& topic, const String& payload);
static void onWsMessage(const String& clientId, const String& message);
static void onWsBinary(const String& clientId, const uint8_t* data, size_t len);
static void sendResponse(const String& target, const String& json);
void setLogMqttEnable(bool enable);
bool isLogMqttEnabled();

static LogManager::LogCallback s_logCb;
static bool _logMqttActive = false;

void setLogMqttEnable(bool enable) {
    _logMqttActive = enable;
    if (enable && s_logCb) {
        logCaptureFlushCallback(s_logCb);
    }
}

bool isLogMqttEnabled() {
    return _logMqttActive;
}

// ── WebSockets Mutex (Cooperative SpinLock) ──
static volatile bool g_wsLocked = false;

static void safeWsBroadcast(const String& json) {
    while (g_wsLocked) {
        vTaskDelay(1);
    }
    g_wsLocked = true;
    wsServer.broadcast(json);
    g_wsLocked = false;
}

// ── Setup ──
void setup() {
    delay(10);
   
    LT_IM(SYS, "=== Smart Home Controller ===");
    LT_IM(SYS, "FW Build: %s", buildStr());
    LT_IM(SYS, "Boot Reason: %s", chip::systemResetReason().c_str());
    LT_IM(SYS, "Free Heap: %u bytes", ESP.getFreeHeap());
    

    //Watchdog: 15s timeout, feeder task feed mỗi 2s
    if (compat::wdtEnable(WDT_TIMEOUT_MS)) {
        xTaskCreate(taskWdtFeed, "wdtFeed", TASK_WDT_STACK, NULL, TASK_WDT_PRIO, NULL);
        LT_IM(SYS, "Watchdog enabled, 15s timeout");
    }
    else {
        LT_IM(SYS, "Watchdog not supported");
    }

    //LITTLEFS.format();
    if (!LITTLEFS.begin()) {
        LT_IM(SYS, "LittleFS mount failed, formatting...");
        LITTLEFS.format();
        if (!LITTLEFS.begin()) {
            LT_IM(SYS, "LittleFS still failed!");
        }
    }

    g_identity.begin(profileName());

    // Seed mã hóa = deviceId + FW_SECRET (FW_SECRET từ .env qua build flag,
    // xem scripts/build_env.py). Dùng chung cho controlKey (identity blob) và
    // mqttPass (config). FW_SECRET rỗng → chỉ deviceId (compat build cũ).
    g_encSeed = String(g_identity.deviceId()) + FW_SECRET;
    g_identity.setEncSeed(g_encSeed.c_str());
    configManager.setEncSeed(g_encSeed.c_str());

    if (!configManager.load(configManager.get())) {
        LT_IM(CFG, "No config found, using defaults");
    }
    ProfileConfig& cfg = configManager.get();

    logManager.begin();
    logManager.setSysLogFileEnabled(cfg.connMode != ConnMode::DEBUG_WS && cfg.sysLogFileEnabled);
    logManager.setSysLogFileLevel(cfg.sysLogFileLevel);
    logCaptureFlushFile(&logManager);

    s_logCb = [](const String& line) {
        JsonDocument logJson;
        logJson["cmd"] = "log";
        logJson["msg"] = line;
        String json;
        serializeJson(logJson, json);
        if (g_connMode == ConnMode::DEBUG_WS || g_connMode == ConnMode::AP_WS) {
            safeWsBroadcast(json);
        }
        if (_logMqttActive) {
            mqttClient.publish(mqttBaseTopic() + "/log", json);
        }
        };
    logManager.setLogCallback(s_logCb);

    configManager.print();

    // Driver thiết bị (theo profile) — wiring + calib + relayStartMode nằm trong driver
    g_driver = createDriver();
    g_driver->setServices({
        &logManager,
        [&]() { return configManager.save(); },
        [&]() { configManager.reset(); g_identity.reset(); },
        [](const String& json) {
            if (g_connMode == ConnMode::STA_MQTT) {
                mqttClient.publish(mqttBaseTopic() + "/up", json);
            }
            else {
                safeWsBroadcast(json);
            }
        },
        []() { return g_connMode == ConnMode::STA_MQTT && mqttClient.isConnected(); },
        [](const String& topic, const String& payload) {
            if (g_connMode != ConnMode::STA_MQTT) return false;
            return mqttClient.publish(topic, payload);
        },
        [](const String& topic) { subscribeMqttTopic(topic); },
        [&]() { commandHandler.publishStatusToUp(); },
        g_identity.deviceId()
    });
    g_driver->begin(configManager.get(), [&]() { return configManager.save(); });

    otaManager.begin();

    commandHandler.begin(&configManager, &logManager, &otaManager, &g_identity, profileName());
    commandHandler.setDriver(g_driver);
    commandHandler.setResponseCallback(sendResponse);

    compat::pmDisableUnusedClocks();
    //ln_pm_sleep_mode_set(LIGHT_SLEEP);

    // Connection-specific setup
    // Device luôn tự sinh secret+controlKey lúc boot đầu (isProvisioned()=true),
    // nên AP pairing portal myhome-<model>-XXXX được điều khiển bằng connMode default=AP_WS
    // (device mới / sau factoryReset). STA_MQTT chỉ khi đã pair thành công.
    ConnMode bootMode = cfg.connMode;
    switch (bootMode) {
    case ConnMode::AP_WS:
        setupAP_WS(cfg);
        xTaskCreate(taskWsLoop, "ws", TASK_NETWORK_STACK, NULL, TASK_NETWORK_PRIO, NULL);
        break;
    case ConnMode::STA_MQTT:
        setupSTA_MQTT(cfg);
        xTaskCreate(taskWifiConnect, "wifiConn", TASK_NETWORK_STACK, NULL, TASK_NETWORK_PRIO, NULL);
        break;
    case ConnMode::DEBUG_WS:
        setupDEBUG_WS(cfg);
        xTaskCreate(taskWifiConnect, "wifiConn", TASK_NETWORK_STACK, NULL, TASK_NETWORK_PRIO, NULL);
        break;
    }

    xTaskCreate(driverTask, "driver", TASK_SENSOR_STACK, NULL, TASK_SENSOR_PRIO, NULL);
    xTaskCreate(taskStreamSender, "stream", TASK_STREAM_STACK, NULL, TASK_STREAM_PRIO, NULL);

    LT_IM(SYS, "System ready!");

}

void loop() {
    compatRunSchedulerStep();
}

// ── Connection setup functions ──

void setupWiFiSTA(ProfileConfig& cfg) {
    WiFi.mode(WIFI_STA);
    WiFi.setHostname("iphone");
    if (g_connMode == ConnMode::STA_MQTT) {
        WiFi.config(IPAddress(0, 0, 0, 0), IPAddress(0, 0, 0, 0), IPAddress(0, 0, 0, 0));
        WiFi.begin(cfg.wifiSSID, cfg.wifiPass);
    }
    else if (g_connMode == ConnMode::DEBUG_WS) {
        WiFi.config(
            IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
            IPAddress(cfg.debugGateway[0], cfg.debugGateway[1], cfg.debugGateway[2], cfg.debugGateway[3]),
            IPAddress(cfg.debugNetmask[0], cfg.debugNetmask[1], cfg.debugNetmask[2], cfg.debugNetmask[3]));
        WiFi.begin(cfg.debugSSID, cfg.debugPass);
    }
}

static void setupAP_WS(ProfileConfig& cfg) {
    const char* apPass = DEFAULT_AP_PASSWORD; // WPA2 mặc định
    LT_IM(NET, "AP mode: SSID=%s %s", g_identity.apSSID(), apPass ? "(WPA2)" : "(open)");
    g_connMode = ConnMode::AP_WS;

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(
        IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
        IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
        IPAddress(cfg.debugNetmask[0], cfg.debugNetmask[1], cfg.debugNetmask[2], cfg.debugNetmask[3]));

    //WiFi.softAPConfig(IPAddress(192, 168, 4, 1), IPAddress(192, 168, 4, 1), IPAddress(255, 255, 255, 0));

    chip::softApStart(g_identity.apSSID(), apPass, 1);

    chip::reclaimRelayGpio();

    wsServer.begin();
    wsServer.setCallback(onWsMessage);
    wsServer.setBinaryCallback(onWsBinary);
}

static void setupSTA_MQTT(ProfileConfig& cfg) {
    LT_IM(NET, "STA+MQTT mode: connecting to %s", cfg.wifiSSID);
    g_connMode = ConnMode::STA_MQTT;
    setupWiFiSTA(cfg);

    chip::reclaimRelayGpio();

    String clientId = String("device-") + g_identity.deviceId();
    if (cfg.mqttUser[0] == '\0') {
        LT_EM(NET, "MQTT: no credential (device not paired) — MQTT disabled");
        return;
    }
    mqttClient.begin(cfg.mqttServer, cfg.mqttPort,
        cfg.mqttUser, configManager.passPlain(),
        clientId.c_str(), mqttBaseTopic().c_str());
    mqttClient.setCallback(onMqttMessage);

}

static void setupDEBUG_WS(ProfileConfig& cfg) {
    LT_IM(NET, "Debug mode: connecting to %s", cfg.debugSSID);
    LT_IM(NET, "Debug mode: IP %d.%d.%d.%d", cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]);
    g_connMode = ConnMode::DEBUG_WS;
    setupWiFiSTA(cfg);

    chip::reclaimRelayGpio();
}

// ── Task: WiFi Connect ──
void taskWifiConnect(void* pvParams) {
    (void)pvParams;

    while (1) {
        if (WiFi.status() != WL_CONNECTED) {
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }

        switch (g_connMode) {
        case ConnMode::DEBUG_WS:
            xTaskCreate(taskWsLoop, "ws", TASK_NETWORK_STACK, NULL, TASK_NETWORK_PRIO, NULL);
            break;
        case ConnMode::STA_MQTT:
            xTaskCreate(taskMqttLoop, "mqtt", TASK_NETWORK_STACK, NULL, TASK_NETWORK_PRIO, NULL);
            xTaskCreate(taskNtpUpdate, "ntp", TASK_NTP_STACK, NULL, TASK_NTP_PRIO, NULL);
            break;
        default:
            break;
        }

        WiFi.setSleep(true);
        vTaskDelay(pdMS_TO_TICKS(100));
        vTaskDelete(NULL);
    }
}

// ── Task: WebSocket Loop ──
void taskWsLoop(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();

    LT_IM(NET, "Starting WebSocket server...");

    wsServer.begin();
    wsServer.setCallback(onWsMessage);
    wsServer.setBinaryCallback(onWsBinary);

    LT_IM(NET, "WebSocket server started");

    while (1) {
        compat::wdtFeed();
        switch (g_connMode) {
        case ConnMode::DEBUG_WS:
            wsServer.handle();
            chip::scanPumpDoneEvent();
            if (!logCaptureIsDone() && wsServer.clientCount() > 0 && s_logCb) {
                logCaptureFlushCallback(s_logCb);
            }

            break;

        case ConnMode::AP_WS:
            wsServer.handle();
            chip::scanPumpDoneEvent();
            break;
        }

        vTaskDelayUntil(&lastWake, otaManager.isRunning() ? pdMS_TO_TICKS(5) : pdMS_TO_TICKS(50));
    }
}

// ── Task: MQTT Loop ──
void taskMqttLoop(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();
    bool logLostConnection = true; // Bắt đầu ở trạng thái coi như mất kết nối để khi connect lần đầu sẽ trigger

    while (1) {
        compat::wdtFeed();
        bool connected = mqttClient.loop();
        if (!connected && !logLostConnection) {
            LT_E("MQTT connection lost. Attempting to reconnect...");
            logLostConnection = true;
        }
        else if (connected && logLostConnection) {
            LT_I("MQTT reconnected");
            logLostConnection = false;
            resubscribeMqttTopics();
        }

        vTaskDelayUntil(&lastWake, otaManager.isRunning() ? pdMS_TO_TICKS(10) : pdMS_TO_TICKS(50));
    }
}

// ── Task: NTP Update ──
void taskNtpUpdate(void* pvParams) {
    (void)pvParams;
    ntpClient.begin();
    bool isLogTime = false;

    while (1) {
        ntpClient.update();

        if (ntpClient.isTimeSet()) {
            logManager.setTime(ntpClient.getEpochTime());
            if (!isLogTime) {
                LT_I("NTP time set: %s", ntpClient.getFormattedTime().c_str());

                isLogTime = true;
            }
        }
        vTaskDelay(pdMS_TO_TICKS(ntpClient.isTimeSet() ? 61000 : 5000));
    }
}

// ── Watchdog Feeder Task ──
void taskWdtFeed(void* pvParams) {
    (void)pvParams;

    while (1) {
        if (ESP.getFreeHeap() < HEAP_CRITICAL_BYTES) {
            LT_IM(SYS, "Heap critically low (%u bytes), restarting!", ESP.getFreeHeap());
            ESP.restart();
            while (1) {}
        }
        compat::wdtFeed();
        
        vTaskDelay(pdMS_TO_TICKS(WDT_FEED_INTERVAL_MS));
    }
}

// ── Driver Task ──
void driverTask(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();

    while (1) {
        g_driver->loop(millis());
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(20));
    }
}

// ── Stream Sender Task ──
// Cũng chịu trách nhiệm publish status định kỳ lên devices/{id}/up để các
// thiết bị theo dõi (vd Remote Switch) luôn nhận được dữ liệu mới.
// Chỉ tự báo khi KHÔNG có status stream (mỗi 60s): đang stream thì để stream
// tự chạy (2s), tránh publish trùng 2 bản/lần.
void taskStreamSender(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();
    uint32_t lastStatusUp = 0;

    while (1) {
        commandHandler.sendStream(CommandHandlerT<ProfileConfig>::STREAM_STATUS);
        commandHandler.sendStream(CommandHandlerT<ProfileConfig>::STREAM_SYSINFO);

        uint32_t nowMs = millis();
        if (!commandHandler.isStreamActive(CommandHandlerT<ProfileConfig>::STREAM_STATUS)) {
            if (nowMs - lastStatusUp >= 60000) {
                lastStatusUp = nowMs;
                commandHandler.publishStatusToUp();
            }
        }
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(2000));
    }
}

// ── Callbacks ──

static void onMqttMessage(const String& topic, const String& payload) {
    // Topic của chính mình (devices/{id}/cmd|otachunk|down) → lệnh cho CommandHandler
    if (topic.startsWith(mqttBaseTopic() + "/")) {
        commandHandler.handleCommand("mqtt", payload);
        return;
    }

    // Topic do driver đăng ký (vd: devices/{targetId}/up của Remote Switch)
    // → đẩy thẳng cho driver; topic lạ khác bị bỏ qua.
    if (isExtraMqttTopic(topic)) {
        if (g_driver) {
            JsonDocument doc;
            deserializeJson(doc, payload);
            g_driver->handleTargetStatus(doc);
        }
    }
}

static void onWsMessage(const String& clientId, const String& message) {
    (void)clientId;
    //LT_IM(WS, "Received message: %s", message.c_str());
    commandHandler.handleCommand("ws", message);
}

static void onWsBinary(const String& clientId, const uint8_t* data, size_t len) {
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
        safeWsBroadcast(json);
    }
}
