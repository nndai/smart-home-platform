#include <Arduino.h>
#include "compat/log.h"
#include "compat/task.h"
#include <Config.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <NTPClient.h>
#include "compat/wdt.h"

#include "core/ConfigManager.h"
#include "core/LedController.h"
#include <OneButton.h>
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
DeviceDriver* g_driver = nullptr;
LedController ledController;
OneButton button;
MqttClient mqttClient;
WebSocketServer wsServer(WEBSOCKET_PORT);
LogManager logManager;
WiFiUDP ntpUdp;
NTPClient ntpClient(ntpUdp, 7 * 3600);
OTAManager otaManager;
CommandHandlerT<ProfileConfig> commandHandler;

template class CommandHandlerT<ProfileConfig>;


// ── Runtime connection mode ──
ConnMode g_connMode = ConnMode::AP_WS;

// ── Forward declarations ──
void taskWifiConnect(void* pvParams);
void taskWsLoop(void* pvParams);
void taskMqttLoop(void* pvParams);
void taskNtpUpdate(void* pvParams);
void driverTask(void* pvParams);
void buttonTask(void* pvParams);
void ledTask(void* pvParams);
void taskWdtFeed(void* pvParams);
void taskStreamSender(void* pvParams);

static void setupAP_WS(ProfileConfig& cfg);
static void setupSTA_MQTT(ProfileConfig& cfg);
static void setupDEBUG_WS(ProfileConfig& cfg);
static void onMqttMessage(const String& topic, const String& payload);
static void onWsMessage(const String& clientId, const String& message);
static void onWsBinary(const String& clientId, const uint8_t* data, size_t len);
static void onButtonClick();
static void onButtonDoubleClick();
static void onButtonLongPressStart();
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

// ── Setup ──
void setup() {

    delay(10);
    LT_IM(SYS, "=== Remote Pump Controller LN882H ===");
    LT_IM(SYS, "FW Version: %s (build %s, %u)", FIRMWARE_VERSION, buildStr(), (unsigned)buildUnixTime());

    //Watchdog: 15s timeout, feeder task feed mỗi 2s
    if (compat::wdtEnable(WDT_TIMEOUT_MS)) {
        xTaskCreate(taskWdtFeed, "wdtFeed", 512, NULL, tskIDLE_PRIORITY + 1, NULL);
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

    //── Seed test data ──
    // {
    //     for (const char* d : {"/logs/sys/", "/logs/toggle/", "/logs/power/"}) {
    //         if (!LITTLEFS.exists(d)) LITTLEFS.mkdir(d);
    //     }

    //     auto writeFile = [](const char* path, const char* data) {
    //         File f = LITTLEFS.open(path, "w");
    //         if (f) { f.print(data); f.close(); }
    //     };

    //     writeFile("/logs/toggle/23-07-2026.log",
    //         "00:00:01|0|1\n00:00:02|0|0\n01:15:30|1|1\n"
    //         "02:30:00|2|1\n03:45:15|0|0\n04:00:00|1|0\n"
    //         "05:10:45|0|1\n06:20:30|2|0\n07:35:00|0|1\n"
    //         "08:45:15|1|1\n09:55:30|0|0\n10:05:45|2|1\n"
    //         "11:15:00|0|1\n12:25:15|1|0\n13:35:30|0|1\n"
    //         "14:45:45|2|1\n15:55:00|0|0\n16:05:15|1|1\n"
    //         "17:15:30|0|0\n18:25:45|2|0\n19:35:00|0|1\n"
    //         "20:45:15|1|1\n21:55:30|0|0\n22:05:45|2|1\n"
    //         "23:15:00|0|0\n");

    //     writeFile("/logs/toggle/24-07-2026.log",
    //         "00:00:05|0|1\n01:10:20|0|0\n02:20:35|1|1\n"
    //         "03:30:50|2|0\n04:41:05|0|1\n05:51:20|1|0\n"
    //         "06:01:35|0|1\n07:11:50|2|1\n08:22:05|0|0\n"
    //         "09:32:20|1|1\n10:42:35|0|1\n11:52:50|2|0\n"
    //         "12:03:05|0|0\n13:13:20|1|1\n14:23:35|0|1\n"
    //         "15:33:50|2|1\n16:44:05|0|0\n17:54:20|1|0\n"
    //         "18:04:35|0|1\n19:14:50|2|1\n20:25:05|0|0\n"
    //         "21:35:20|1|1\n22:45:35|0|0\n23:55:50|2|0\n");

    //     writeFile("/logs/power/23-07-2026.log",
    //         "0|120\n1|450\n2|380\n3|420\n5|0\n6|210\n"
    //         "7|560\n8|720\n9|690\n10|580\n11|610\n12|450\n13|320\n"
    //         "14|380\n15|420\n16|510\n18|550\n19|620\n"
    //         "20|590\n21|430\n22|210\n23|0\n");

    //     writeFile("/logs/power/24-07-2026.log",
    //         "0|10\n1|0\n2|3200\n3|0\n4|0\n5|0\n7|180\n8|520\n"
    //         "9|680\n10|710\n11|650\n12|590\n13|480\n14|350\n15|400\n"
    //         "16|520\n17|610\n18|580\n19|490\n20|550\n21|620\n"
    //         "22|510\n23|380\n");

    //     LT_IM(SYS, "Seed data written! Remove seed code and re-flash.");
    // }

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
            wsServer.broadcast(json);
        }
        if (_logMqttActive) {
            mqttClient.publish(String(configManager.get().mqttTopic), json);
        }
        };
    logManager.setLogCallback(s_logCb);

    configManager.print();

    // Driver thiết bị (theo profile) — wiring + calib + relayStartMode nằm trong driver
    g_driver = createDriver();
    g_driver->setLed(&ledController);
    g_driver->setLog(&logManager);
    g_driver->begin(configManager.get(), [&]() { return configManager.save(); });

    ledController.begin(PIN_LED, LED_ACTIVE_LOW);
    button.setup(PIN_BUTTON, INPUT_PULLUP, BUTTON_ACTIVE_LOW);

    otaManager.begin();

    commandHandler.begin(&configManager, &logManager, &otaManager);
    commandHandler.setDriver(g_driver);
    commandHandler.setResponseCallback(sendResponse);

    compat::pmDisableUnusedClocks();
    //ln_pm_sleep_mode_set(LIGHT_SLEEP);

    // Connection-specific setup
    switch (cfg.connMode) {
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
    xTaskCreate(buttonTask, "button", TASK_BUTTON_STACK, NULL, TASK_BUTTON_PRIO, NULL);
    xTaskCreate(ledTask, "led", TASK_LED_STACK, NULL, TASK_LED_PRIO, NULL);
    xTaskCreate(taskStreamSender, "stream", 1000, NULL, tskIDLE_PRIORITY + 2, NULL);

    LT_IM(SYS, "System ready!");

}

void loop() {
    vTaskDelete(NULL);
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
    LT_IM(NET, "AP mode: SSID=%s", cfg.apSSID);
    g_connMode = ConnMode::AP_WS;

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
        IPAddress(cfg.debugGateway[0], cfg.debugGateway[1], cfg.debugGateway[2], cfg.debugGateway[3]),
        IPAddress(cfg.debugNetmask[0], cfg.debugNetmask[1], cfg.debugNetmask[2], cfg.debugNetmask[3]));

    chip::softApStart(cfg.apSSID, cfg.apPass);

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

    mqttClient.begin(cfg.mqttServer, cfg.mqttPort, cfg.mqttUser, cfg.mqttPass,
        DEVICE_NAME, cfg.mqttTopic);
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
            xTaskCreate(taskNtpUpdate, "ntp", TASK_NTPCLIENT_STACK, NULL, TASK_NETWORK_PRIO, NULL);
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
            if (!logCaptureIsDone() && wsServer.clientCount() > 0 && s_logCb) {
                logCaptureFlushCallback(s_logCb);
            }

            break;

        case ConnMode::AP_WS:
            wsServer.handle();
            break;
        }

        vTaskDelayUntil(&lastWake, otaManager.isRunning() ? pdMS_TO_TICKS(5) : pdMS_TO_TICKS(50));
    }
}

// ── Task: MQTT Loop ──
void taskMqttLoop(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();
    bool logLostConnection = false;

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
void taskStreamSender(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();

    while (1) {
        commandHandler.sendStream(CommandHandlerT<ProfileConfig>::STREAM_STATUS);
        commandHandler.sendStream(CommandHandlerT<ProfileConfig>::STREAM_SYSINFO);
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(2000));
    }
}

// ── Button Task ──
void buttonTask(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();

    button.attachClick(onButtonClick);
    button.attachDoubleClick(onButtonDoubleClick);
    button.attachLongPressStart(onButtonLongPressStart);
    button.setPressMs(BUTTON_LONG_PRESS_MS);

    while (1) {
        button.tick();
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(20));
    }
}

void ledTask(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();

    while (1) {
        ledController.update();
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(50));
    }
}


// ── Callbacks ──

static void onMqttMessage(const String& topic, const String& payload) {
    // if (topic != configManager.get().mqttTopic + String("/otachunk")) {
    //     LT_I("MQTT Received message: %s", payload.c_str());
    // }

    commandHandler.handleCommand("mqtt", payload);
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

static void onButtonClick() {
    bool on = !g_driver->isRelayOn();
    g_driver->setRelay(on);
    //LT_IM(BTN, "Button click: Turn %s", on ? "ON" : "OFF");
    logManager.logToggle(LogManager::ToggleSource::TOGGLE_BUTTON, on);
    JsonDocument resp;
    resp["cmd"] = "setRelay";
    resp["status"] = "ok";
    resp["state"] = on ? "on" : "off";
    String json;
    serializeJson(resp, json);

    if (g_connMode == ConnMode::STA_MQTT) {
        sendResponse("mqtt", json);
        return;
    }

    sendResponse("ws", json);
}

static void onButtonDoubleClick() {
    LT_IM(BTN, "Button double click");
}

static void onButtonLongPressStart() {
    LT_IM(BTN, "Button long press start");

    uint32_t startTime = millis();
    uint8_t step = 0;

    ledController.blink(100);
    while (digitalRead(PIN_BUTTON) == LOW) {
        vTaskDelay(pdMS_TO_TICKS(100));
        if (millis() - startTime >= BUTTON_LONG_PRESS_MS) {
            ledController.off();
            startTime = millis();

            while (millis() - startTime < BUTTON_CONFIRM_TIMEOUT_MS) {
                vTaskDelay(pdMS_TO_TICKS(100));
                if (digitalRead(PIN_BUTTON) == HIGH) {
                    break;
                }
            }

            if(digitalRead(PIN_BUTTON) == HIGH) {
                break;
            }

            step++;
            ledController.blink(step * 200);
            startTime = millis();
        }
    }
    ledController.off();

    if(step == 0) {
        LT_IM(BTN, "Button long press: Reset WiFi");
        ProfileConfig cfg = configManager.get();
        cfg.connMode = ConnMode::AP_WS;
        configManager.save(cfg);
        vTaskDelay(pdMS_TO_TICKS(1000));
        ESP.restart();
    }
    else if (step == 1) {
        LT_IM(BTN, "Button long press: Enter DEBUG mode");
        ProfileConfig cfg = configManager.get();
        cfg.connMode = ConnMode::DEBUG_WS;
        configManager.save(cfg);
        vTaskDelay(pdMS_TO_TICKS(1000));
        ESP.restart();
    }
    else if (step == 2) {
        LT_IM(BTN, "Button long press: Factory reset");
        configManager.reset();
        vTaskDelay(pdMS_TO_TICKS(1000));
        ESP.restart();
    }
    LT_IM(BTN, "Button long press: No action for step %d", step);
}

static void sendResponse(const String& target, const String& json) {

    if (target == "mqtt") {
        mqttClient.publish(configManager.get().mqttTopic, json);
    }
    if (target == "ws") {
        wsServer.broadcast(json);
    }
}
