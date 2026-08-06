#include <Arduino.h>
#include <FreeRTOS.h>
#include <task.h>
#include <queue.h>
#include <semphr.h>
#include <Config.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <NTPClient.h>
#include <WDT.h>
#include <sdk_private.h>

#include "ConfigManager.h"
#include "CurrentSensor.h"
#include "TemperatureSensor.h"
#include "RelayController.h"
#include "LedController.h"
#include <OneButton.h>
#include "PumpController.h"
#include "MqttClient.h"
#include "WebSocketServer.h"
#include "log/LogManager.h"
#include "OTAManager.h"
#include "CommandHandler.h"
#include "BuildInfo.h"
#include "utils/power_mgmt/ln_pm.h"
#include <hal/hal_gpio.h>


// ── Global objects ──
ConfigManager configManager;
CurrentSensor currentSensor;
TemperatureSensor tempSensor;
RelayController relayController;
LedController ledController;
OneButton button;
PumpController pumpController;
MqttClient mqttClient;
WebSocketServer wsServer(WEBSOCKET_PORT);
LogManager logManager;
WiFiUDP ntpUdp;
NTPClient ntpClient(ntpUdp, 7 * 3600);
OTAManager otaManager;
CommandHandler commandHandler;


// ── Runtime connection mode ──
ConnMode g_connMode = ConnMode::AP_WS;

// ── Forward declarations ──
void taskWifiConnect(void* pvParams);
void taskWsLoop(void* pvParams);
void taskMqttLoop(void* pvParams);
void taskNtpUpdate(void* pvParams);
void sensorTask(void* pvParams);
void buttonTask(void* pvParams);
void ledTask(void* pvParams);
void taskWdtFeed(void* pvParams);
void taskEnergyLog(void* pvParams);
void taskStreamSender(void* pvParams);

// ── Energy log tracking ──
static uint32_t s_lastHourEpoch = UINT32_MAX;
static uint32_t s_hourRefMillis = 0;
static uint32_t s_lastDayEpoch = UINT32_MAX;

static void setupAP_WS(DeviceConfig& cfg);
static void setupSTA_MQTT(DeviceConfig& cfg);
static void setupDEBUG_WS(DeviceConfig& cfg);
static void onMqttMessage(const String& topic, const String& payload);
static void onWsMessage(const String& clientId, const String& message);
static void onWsBinary(const String& clientId, const uint8_t* data, size_t len);
static void onPumpState(PumpState state, float current, bool isOn, const char* msg);
static void onButtonClick();
static void onButtonDoubleClick();
static void onButtonLongPressStart();
static void sendResponse(const String& target, const String& json);
static void reclaimRelayGpio();
void setLogMqttEnable(bool enable);
bool isLogMqttEnabled();
extern "C" void logCaptureFlushFile(LogManager* lm);
extern "C" void logCaptureFlushCallback(LogManager::LogCallback cb);
extern "C" bool logCaptureIsDone();

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
    if (WDT.enable(WDT_TIMEOUT_MS)) {
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
    DeviceConfig& cfg = configManager.get();

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

    currentSensor.begin(PIN_BL0937_CF, PIN_BL0937_CF1, PIN_BL0937_SEL);
    if (isnan(cfg.cCal) || isnan(cfg.vCal) || isnan(cfg.pCal)) {
        cfg.cCal = currentSensor.getCurrentMultiplier();
        cfg.vCal = currentSensor.getVoltageMultiplier();
        cfg.pCal = currentSensor.getPowerMultiplier();
        configManager.save(cfg);
    }
    else {
        currentSensor.setCurrentMultiplier(cfg.cCal);
        currentSensor.setVoltageMultiplier(cfg.vCal);
        currentSensor.setPowerMultiplier(cfg.pCal);
    }


    tempSensor.begin(PIN_NTC_ADC);
    relayController.begin(PIN_RELAY, PIN_TRIAC_GATE);
    ledController.begin(PIN_LED, LED_ACTIVE_LOW);
    button.setup(PIN_BUTTON, INPUT_PULLUP, BUTTON_ACTIVE_LOW);

    pumpController.begin(&relayController, cfg.pumpMode);
    pumpController.setThresholds(cfg.threshOff, cfg.threshNoWater, cfg.threshRunning, cfg.threshOverload);
    pumpController.setTimeouts(cfg.dryTimeout, cfg.overloadTimeout);
    pumpController.setEventCallback(onPumpState);


    otaManager.begin();

    commandHandler.begin(&configManager, &currentSensor, &tempSensor,
        &pumpController, &logManager, &otaManager);
    commandHandler.setResponseCallback(sendResponse);

    ln_pm_always_clk_disable_select(CLK_G_I2S | CLK_G_WS2811 | CLK_G_SDIO | CLK_G_AES);
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

    if (cfg.relayStartMode == RelayStartMode::ON) {
        pumpController.turnOn();
    }
    else if (cfg.relayStartMode == RelayStartMode::OFF) {
        pumpController.turnOff();
    }
    else {
        // LAST: TODO
        pumpController.turnOff();
    }

    xTaskCreate(sensorTask, "sensor", TASK_SENSOR_STACK, NULL, TASK_SENSOR_PRIO, NULL);
    xTaskCreate(buttonTask, "button", TASK_BUTTON_STACK, NULL, TASK_BUTTON_PRIO, NULL);
    xTaskCreate(ledTask, "led", TASK_LED_STACK, NULL, TASK_LED_PRIO, NULL);
    xTaskCreate(taskEnergyLog, "energyLog", 1000, NULL, tskIDLE_PRIORITY + 1, NULL);
    xTaskCreate(taskStreamSender, "stream", 1000, NULL, tskIDLE_PRIORITY + 2, NULL);

    LT_IM(SYS, "System ready!");

}

void loop() {
    vTaskDelete(NULL);
}

// ── Connection setup functions ──

void setupWiFiSTA(DeviceConfig& cfg) {
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

static void reclaimRelayGpio() {
    uint32_t bases[2] = { GPIOB_BASE, GPIOA_BASE };
    gpio_pin_t pins[2] = { GPIO_PIN_3, GPIO_PIN_8 };
    for (int i = 0; i < 2; i++) {
        hal_gpio_pin_afio_en(bases[i], pins[i], HAL_DISABLE);
        gpio_init_t_def cfg;
        cfg.pin = pins[i];
        cfg.speed = GPIO_NORMAL_SPEED;
        cfg.mode = GPIO_MODE_DIGITAL;
        cfg.dir = GPIO_OUTPUT;
        cfg.pull = GPIO_PULL_NONE;
        hal_gpio_init(bases[i], &cfg);
        hal_gpio_pin_reset(bases[i], pins[i]);
    }
}

static void setupAP_WS(DeviceConfig& cfg) {
    LT_IM(NET, "AP mode: SSID=%s", cfg.apSSID);
    g_connMode = ConnMode::AP_WS;

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(IPAddress(cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]),
        IPAddress(cfg.debugGateway[0], cfg.debugGateway[1], cfg.debugGateway[2], cfg.debugGateway[3]),
        IPAddress(cfg.debugNetmask[0], cfg.debugNetmask[1], cfg.debugNetmask[2], cfg.debugNetmask[3]));

    static uint8_t psk[40] = { 0 };
    ln_psk_calc(cfg.apSSID, cfg.apPass, psk, sizeof(psk));

    static uint8_t ap_mac[6];
    WiFi.softAPmacAddress(ap_mac);
    

    typedef struct {
        char* ssid;
        char* pwd;
        uint8_t* bssid;
        uint8_t channel;
        uint8_t authmode;
        uint8_t ssid_hidden;
        uint8_t _pad1;          //padding
        uint16_t beacon_interval;
        uint8_t _pad2[2];       // padding
        uint8_t* psk_value;
    } ap_cfg_manual_t;
    
    ap_cfg_manual_t ap_cfg = {};
    ap_cfg.ssid = cfg.apSSID;
    ap_cfg.pwd = cfg.apPass;
    ap_cfg.bssid = ap_mac;
    ap_cfg.channel = 1;
    ap_cfg.authmode = 3;    // WPA2_PSK
    ap_cfg.beacon_interval = 5000;
    ap_cfg.psk_value = psk;

    int r = wifi_softap_start((wifi_softap_cfg_t*)&ap_cfg);
    if (r != 0) {
        LT_EM(NET, "SoftAP SDK failed: %d, fallback to WiFi.softAP()", r);
        WiFi.softAP(cfg.apSSID, cfg.apPass);
    }

    reclaimRelayGpio();

    wsServer.begin();
    wsServer.setCallback(onWsMessage);
    wsServer.setBinaryCallback(onWsBinary);
}

static void setupSTA_MQTT(DeviceConfig& cfg) {
    LT_IM(NET, "STA+MQTT mode: connecting to %s", cfg.wifiSSID);
    g_connMode = ConnMode::STA_MQTT;
    setupWiFiSTA(cfg);

    reclaimRelayGpio();

    mqttClient.begin(cfg.mqttServer, cfg.mqttPort, cfg.mqttUser, cfg.mqttPass,
        DEVICE_NAME, cfg.mqttTopic);
    mqttClient.setCallback(onMqttMessage);

}

static void setupDEBUG_WS(DeviceConfig& cfg) {
    LT_IM(NET, "Debug mode: connecting to %s", cfg.debugSSID);
    LT_IM(NET, "Debug mode: IP %d.%d.%d.%d", cfg.debugIp[0], cfg.debugIp[1], cfg.debugIp[2], cfg.debugIp[3]);
    g_connMode = ConnMode::DEBUG_WS;
    setupWiFiSTA(cfg);

   reclaimRelayGpio();
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
        WDT.feed();
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
        WDT.feed();
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
        WDT.feed();

        vTaskDelay(pdMS_TO_TICKS(WDT_FEED_INTERVAL_MS));
    }
}

// ── Energy Log Task ──
void taskEnergyLog(void* pvParams) {
    (void)pvParams;
    uint32_t time = 0;

    while (1) {
        if (logManager.isTimeSynced()) {
            uint32_t epoch = logManager.getEpoch();
            uint32_t h = epoch / 3600;

            if (h != s_lastHourEpoch && s_lastHourEpoch != UINT32_MAX) {
                uint32_t wh = currentSensor.getHourlyEnergy(); // Wh
                if (wh > 0) {
                    uint8_t label = s_lastHourEpoch % 24;
                    time_t intervalStart = (time_t)s_lastHourEpoch * 3600;
                    logManager.logHourlyPower(label, wh, intervalStart);
                    logManager.addTotalPower(wh);
                }
                currentSensor.resetHourlyEnergy();
            }
            s_lastHourEpoch = h;
            s_hourRefMillis = millis();

            uint32_t d = epoch / 86400;
            if (d != s_lastDayEpoch && s_lastDayEpoch != UINT32_MAX) {
                currentSensor.resetDailyEnergy();
            }
            s_lastDayEpoch = d;
        }
        else {
            if (millis() - s_hourRefMillis >= 3600000UL) {
                uint32_t wh = currentSensor.getHourlyEnergy(); // Wh
                if (wh > 0) {
                    logManager.addTotalPower(wh);
                }
                currentSensor.resetHourlyEnergy();
                s_hourRefMillis = millis();
            }
        }

        if (millis() - time >= 3600000UL) {
            time = millis();
            logManager.addPumpTime(3600000UL);
        }

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

// ── Stream Sender Task ──
void taskStreamSender(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();

    while (1) {
        commandHandler.sendStream(CommandHandler::STREAM_STATUS);
        commandHandler.sendStream(CommandHandler::STREAM_SYSINFO);
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(2000));
    }
}

// ── Sensor Task ──
void sensorTask(void* pvParams) {
    (void)pvParams;
    TickType_t lastWake = xTaskGetTickCount();
    unsigned long lastMonitorLoop = 0;

    while (1) {
        unsigned long now = millis();
        if (now - lastMonitorLoop >= 1000) {
            currentSensor.loop();
            lastMonitorLoop = now;
        }
        float current = currentSensor.getCurrent(); // A
        pumpController.update(current);
        vTaskDelayUntil(&lastWake, pdMS_TO_TICKS(20));
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

static void onPumpState(PumpState state, float current, bool isOn, const char* msg) {
    //LT_IM(PUMP, "%s (%.2fA)", msg, current);

    if(state == PumpState::DRY_RUN) {
        ledController.blink(500);
    }
    else if(state == PumpState::OVERLOAD) {
        ledController.blink(200);
    }
    else if(state == PumpState::HIGH_CURRENT) {
        ledController.blink(2, 500, 3000);
    }
    else if(state == PumpState::CRITICAL_CURRENT) {
        ledController.blink(3, 200, 1000);
    }
    else if (isOn == false) {
        ledController.off();
    }
    else if (isOn == true) {
        ledController.on();
    }
}

static void onButtonClick() {
    bool on = !pumpController.isOn();
    pumpController.toggle();
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
        DeviceConfig cfg = configManager.get();
        cfg.connMode = ConnMode::AP_WS;
        configManager.save(cfg);
        vTaskDelay(pdMS_TO_TICKS(1000));
        ESP.restart();
    }
    else if (step == 1) {
        LT_IM(BTN, "Button long press: Enter DEBUG mode");
        DeviceConfig cfg = configManager.get();
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
