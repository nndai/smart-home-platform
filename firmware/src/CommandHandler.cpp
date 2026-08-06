#include "CommandHandler.h"
#include <mbedtls/base64.h>
#include "BuildInfo.h"

extern "C" {
struct ln_list_s {
    struct ln_list_s *next;
    struct ln_list_s *prev;
};
typedef struct ln_list_s ln_list_t;

#define AP_LIST_NODE_MAX 40

struct ap_info_s {
    uint8_t  bssid[6];
    char     ssid[33];
    uint8_t  channel;
    uint8_t  authmode;
    uint8_t  imode;
    int8_t   rssi;
    int16_t  freq_offset;
    uint8_t  bgn;
    uint8_t  wps_en : 1;
    uint8_t  is_hidden : 1;
    uint8_t  rsn_mfpr : 1;
    uint8_t  rsn_mfpc : 1;
    uint8_t  set_wpa_sae_support : 1;
};
typedef struct ap_info_s ap_info_t;

typedef struct ap_info_node_s {
    ln_list_t   list;
    ap_info_t   info;
    uint32_t    life_ticks;
} ap_info_node_t;

int wifi_manager_get_ap_list(ln_list_t **list, uint8_t *node_count);
}

#define LN_LIST_ENTRY(node, type, field) \
    ((type *)((uint8_t *)(node) - (uint32_t)(&(((type *)0)->field))))
#define LN_LIST_FOR_EACH_ENTRY(entry, type, field, list) \
    for (entry = LN_LIST_ENTRY((list)->next, type, field); \
        &entry->field != (list); \
        entry = LN_LIST_ENTRY(entry->field.next, type, field))

typedef enum { ADC_CH0 = 1 << 0 } adc_ch_t;
extern "C" uint16_t cal_adc_read(adc_ch_t ch);

extern ConnMode g_connMode;

CommandHandler::CommandHandler()
    : _cfg(nullptr)
    , _current(nullptr)
    , _temp(nullptr)
    , _pump(nullptr)
    , _log(nullptr)
    , _ota(nullptr)
{
}

void CommandHandler::begin(ConfigManager* cfg, CurrentSensor* current, TemperatureSensor* temp,
    PumpController* pump, LogManager* log,
    OTAManager* ota) {
    _cfg = cfg;
    _current = current;
    _temp = temp;
    _pump = pump;
    _log = log;
    _ota = ota;
}

void CommandHandler::setResponseCallback(ResponseCallback cb) {
    _responseCb = cb;
}

void CommandHandler::handleCommand(const String& source, const String& json) {
    JsonDocument doc;
    DeserializationError err = deserializeJson(doc, json);
    if (err) {
        JsonDocument resp;
        resp["status"] = "error";
        resp["message"] = "Invalid JSON";
        _sendResponse(source, resp);
        return;
    }

    const char* cmd = doc["cmd"];
    JsonVariant payloadVar = doc["payload"];

    if (!cmd) {
        JsonDocument resp;
        resp["status"] = "error";
        resp["message"] = "Missing cmd";
        _sendResponse(source, resp);
        return;
    }

    JsonDocument payload;
    if (!payloadVar.isNull()) {
        payload = payloadVar.as<JsonObject>();
    }

    _handleCommand(source, doc, payload);
}

void CommandHandler::_sendResponse(const String& source, const String& json) {
    if (_responseCb) _responseCb(source, json);
}

void CommandHandler::_sendResponse(const String& source, const JsonDocument& doc) {
    String json;
    serializeJson(doc, json);
    _sendResponse(source, json);
}

void CommandHandler::_handleCommand(const String& source, const JsonDocument& cmd, const JsonDocument& payload) {
    String cmdStr = cmd["cmd"].as<String>();
    String reqId = cmd["reqId"].is<String>() ? cmd["reqId"].as<String>() : "";

    JsonDocument resp;
    resp["cmd"] = cmdStr;
    
    if (reqId.length() > 0) resp["reqId"] = reqId;

    if (cmdStr == "setRelay") _cmdSetRelay(source, payload, resp);
    else if (cmdStr == "getStatus") _cmdGetStatus(source, payload, resp);
    else if (cmdStr == "getConfig") _cmdGetConfig(source, payload, resp);
    else if (cmdStr == "setConfig") _cmdSetConfig(source, payload, resp);
    else if (cmdStr == "getLog") _cmdGetLog(source, payload, resp);
    else if (cmdStr == "clearSysLog") _cmdClearSysLog(source, payload, resp);
    else if (cmdStr == "otaUrl") _cmdOtaUrl(source, payload, resp);
    else if (cmdStr == "reboot") _cmdReboot(source, payload, resp);
    else if (cmdStr == "factoryReset") _cmdFactoryReset(source, payload, resp);
    else if (cmdStr == "calibrate") _cmdCalibrate(source, payload, resp);
    else if (cmdStr == "resetCalibration") _cmdResetCalibration(source, payload, resp);
    else if (cmdStr == "setLogMqtt") _cmdSetLogMqtt(source, payload, resp);
    else if (cmdStr == "getLogMqtt") _cmdGetLogMqtt(source, payload, resp);
    else if (cmdStr == "getLogStats") _cmdGetLogStats(source, payload, resp);
    else if (cmdStr == "getSystemInfo") _cmdGetSystemInfo(source, payload, resp);
    else if (cmdStr == "uploadFirmwareStart") _cmdUploadFirmwareStart(source, payload, resp);
    else if (cmdStr == "uploadFirmwareEnd") _cmdUploadFirmwareEnd(source, payload, resp);
    else if (cmdStr == "uploadFirmwareAbort") _cmdUploadFirmwareAbort(source, payload, resp);
    else if (cmdStr == "otaChunk") _cmdOtaChunk(source, payload, resp);
    else if (cmdStr == "clearPumpFault") _cmdClearPumpFault(source, payload, resp);
    else if (cmdStr == "scanWifi") _cmdScanWifi(source, payload, resp);
    else if (cmdStr == "getScanWifiData") _cmdGetScanWifiData(source, payload, resp);
    else if (cmdStr == "listDir" || cmdStr == "readFile" || cmdStr == "fileInfo" || cmdStr == "deleteItem" || cmdStr == "fsInfo" || cmdStr == "downloadFile") {
        _handleFileCommand(source, cmdStr, payload, reqId);
        return;
    }
    else {
        resp["status"] = "error";
        resp["message"] = "Unknown command";
        _sendResponse(source, resp);
    }
}

void CommandHandler::startStream(StreamType type, const String& source, unsigned long durationMs) {
    if (type >= STREAM_COUNT) return;
    _streamDeadline[type] = millis() + durationMs;
    _streamSource[type] = source;
}

bool CommandHandler::isStreamActive(StreamType type) const {
    if (type >= STREAM_COUNT) return false;
    return _streamSource[type].length() > 0 && millis() < _streamDeadline[type];
}

bool CommandHandler::anyStreamActive() const {
    for (int i = 0; i < STREAM_COUNT; i++) {
        if (isStreamActive((StreamType)i)) return true;
    }
    return false;
}

void CommandHandler::sendStream(StreamType type) {
    if (!isStreamActive(type)) return;

    JsonDocument resp;
    JsonDocument emptyPayload;
    const String& source = _streamSource[type];

    switch (type) {
    case STREAM_STATUS:
        resp["cmd"] = "getStatus";
        _cmdGetStatus(source, emptyPayload, resp);
        break;
    case STREAM_SYSINFO:
        resp["cmd"] = "getSystemInfo";
        emptyPayload["fields"] = "all";
        _cmdGetSystemInfo(source, emptyPayload, resp);
        break;
    }
}

void CommandHandler::_cmdSetRelay(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (!payload["state"].is<bool>()) {
        resp["status"] = "error";
        resp["message"] = "Missing or invalid 'state' field";
        _sendResponse(source, resp);
        return;
    }
    bool on = payload["state"].as<bool>();
    on ? _pump->turnOn() : _pump->turnOff();
    resp["status"] = "ok";
    resp["state"] = on ? "on" : "off";
    LT_IM(CMD, "Relay %s", on ? "ON" : "OFF");
    _log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
    _sendResponse(source, resp);
}

void CommandHandler::_cmdGetStatus(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    
    resp["timestamp"] = _log->getEpoch();
    BL0937SensorData blData = _current->readAll();

    resp["status"] = "ok";
    resp["relay"] = _pump->isOn();
    resp["current"] = blData.current;
    resp["power"] = blData.power;
    resp["voltage"] = blData.voltage;
    resp["dailyEnergy"] = blData.dailyEnergy;
    resp["hourlyEnergy"] = blData.hourlyEnergy;
    resp["apparent"] = blData.apparent;
    resp["pf"] = blData.pf;
    resp["temperature"] = _temp->readCelsius();
    resp["rssi"] = WiFi.RSSI();
    resp["pumpMode"] = _cfg->get().pumpMode;
    switch (_pump->getState()) {
    case PumpState::OFF:
        resp["pumpStateStr"] = "OFF";
        resp["pumpState"] = (int)PumpState::OFF;
        break;
    case PumpState::RUNNING_OK:
        resp["pumpStateStr"] = "RUNNING OK";
        resp["pumpState"] = (int)PumpState::RUNNING_OK;
        break;
    case PumpState::DRY_RUN:
        resp["pumpStateStr"] = "DRY RUN";
        resp["pumpState"] = (int)PumpState::DRY_RUN;
        break;
    case PumpState::HIGH_CURRENT:
        resp["pumpStateStr"] = "HIGH CURRENT";
        resp["pumpState"] = (int)PumpState::HIGH_CURRENT;
        break;
    case PumpState::CRITICAL_CURRENT:
        resp["pumpStateStr"] = "CRITICAL CURRENT";
        resp["pumpState"] = (int)PumpState::CRITICAL_CURRENT;
        break;
    case PumpState::OVERLOAD:
        resp["pumpStateStr"] = "OVERLOAD";
        resp["pumpState"] = (int)PumpState::OVERLOAD;
        break;
    }


    _sendResponse(source, resp);

    if (payload["stream"].is<bool>() && payload["stream"].as<bool>()) {
        startStream(STREAM_STATUS, source, STREAM_DURATION_MS);
    }
}

void CommandHandler::_cmdGetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    DeviceConfig& c = _cfg->get();
    resp["status"] = "ok";

    // Connection mode
    resp["connMode"] = (int)c.connMode;

    // MQTT
    resp["mqttServer"] = c.mqttServer;
    resp["mqttPort"] = c.mqttPort;
    resp["mqttUser"] = c.mqttUser;
    resp["mqttPass"] = strlen(c.mqttPass) > 0 ? "********" : "";
    resp["mqttTopic"] = c.mqttTopic;

    // WiFi
    resp["wifiSSID"] = c.wifiSSID;
    resp["wifiPass"] = strlen(c.wifiPass) > 0 ? "********" : "";
    resp["apSSID"] = c.apSSID;
    resp["apPass"] = strlen(c.apPass) > 0 ? "********" : "";
    resp["debugSSID"] = c.debugSSID;
    resp["debugPass"] = strlen(c.debugPass) > 0 ? "********" : "";

    // Debug network settings
    char ipBuf[16];
    snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", c.debugIp[0], c.debugIp[1], c.debugIp[2], c.debugIp[3]);
    resp["debugIp"] = (const char*)ipBuf;
    snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", c.debugGateway[0], c.debugGateway[1], c.debugGateway[2], c.debugGateway[3]);
    resp["debugGateway"] = (const char*)ipBuf;
    snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", c.debugNetmask[0], c.debugNetmask[1], c.debugNetmask[2], c.debugNetmask[3]);
    resp["debugNetmask"] = (const char*)ipBuf;

    // Pump settings
    resp["pumpMode"] = c.pumpMode;
    resp["threshOff"] = c.threshOff;
    resp["threshNoWater"] = c.threshNoWater;
    resp["threshRunning"] = c.threshRunning;
    resp["threshOverload"] = c.threshOverload;

    resp["dryTimeout"] = c.dryTimeout;
    resp["overloadTimeout"] = c.overloadTimeout;
    resp["relayStartMode"] = (int)c.relayStartMode;

    // Calibration coefficients
    resp["cCal"] = c.cCal;
    resp["vCal"] = c.vCal;
    resp["pCal"] = c.pCal;

    resp["sysLogFileEnabled"] = c.sysLogFileEnabled;
    resp["sysLogFileLevel"] = c.sysLogFileLevel;

    _sendResponse(source, resp);
}

void CommandHandler::_cmdSetConfig(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    DeviceConfig& c = _cfg->get();
    bool changed = false;
    bool needReboot = false;

    // Connection mode
    if (payload["connMode"].is<unsigned int>()) {
        int v = payload["connMode"].as<int>();
        if (v >= 0 && v <= 2) {
            c.connMode = (ConnMode)v;
            changed = true;
            needReboot = true;
        }
    }

    // MQTT settings
    if (payload["mqttServer"].is<const char*>()) {
        strlcpy(c.mqttServer, payload["mqttServer"], sizeof(c.mqttServer));
        changed = true;
        needReboot = true;
    }
    if (payload["mqttPort"].is<unsigned int>()) { 
        c.mqttPort = payload["mqttPort"]; 
        changed = true;
        needReboot = true;
    }
    if (payload["mqttUser"].is<const char*>()) {
        strlcpy(c.mqttUser, payload["mqttUser"], sizeof(c.mqttUser));
        changed = true;
        needReboot = true;
    }
    if (payload["mqttPass"].is<const char*>()) { 
        strlcpy(c.mqttPass, payload["mqttPass"], sizeof(c.mqttPass)); 
        changed = true; 
        needReboot = true;
    }
    if (payload["mqttTopic"].is<const char*>()) { 
        strlcpy(c.mqttTopic, payload["mqttTopic"], sizeof(c.mqttTopic)); 
        changed = true; 
        needReboot = true;
    }

    // WiFi STA settings
    if (payload["wifiSSID"].is<const char*>()) { 
        strlcpy(c.wifiSSID, payload["wifiSSID"], sizeof(c.wifiSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload["wifiPass"].is<const char*>()) { 
        strlcpy(c.wifiPass, payload["wifiPass"], sizeof(c.wifiPass)); 
        changed = true; 
        needReboot = true;
    }

    // WiFi AP settings
    if (payload["apSSID"].is<const char*>()) { 
        strlcpy(c.apSSID, payload["apSSID"], sizeof(c.apSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload["apPass"].is<const char*>()) { 
        strlcpy(c.apPass, payload["apPass"], sizeof(c.apPass)); 
        changed = true; 
        needReboot = true; 
    }

    // WiFi DEBUG settings
    if (payload["debugSSID"].is<const char*>()) { 
        strlcpy(c.debugSSID, payload["debugSSID"], sizeof(c.debugSSID)); 
        changed = true; 
        needReboot = true;
    }
    if (payload["debugPass"].is<const char*>()) { 
        strlcpy(c.debugPass, payload["debugPass"], sizeof(c.debugPass)); 
        changed = true; 
        needReboot = true;
    }

    // Pump settings
    if (payload["pumpMode"].is<bool>()) { c.pumpMode = payload["pumpMode"];  
        _pump->setPumpMode(c.pumpMode);
        changed = true;
    }
    if (payload["dryTimeout"].is<unsigned int>()) { 
        c.dryTimeout = payload["dryTimeout"]; 
        _pump->setTimeouts(c.dryTimeout, c.overloadTimeout);
        changed = true;
    }
    if (payload["overloadTimeout"].is<unsigned int>()) { 
        c.overloadTimeout = payload["overloadTimeout"]; 
        _pump->setTimeouts(c.dryTimeout, c.overloadTimeout);
        changed = true;
    }
    if (payload["threshOff"].is<unsigned int>()) { 
        c.threshOff = payload["threshOff"]; 
        _pump->setThresholds(c.threshOff, c.threshNoWater, c.threshRunning, c.threshOverload);
        changed = true; 
    }
    if (payload["threshNoWater"].is<unsigned int>()) { 
        c.threshNoWater = payload["threshNoWater"]; 
        _pump->setThresholds(c.threshOff, c.threshNoWater, c.threshRunning, c.threshOverload);
        changed = true;
    }
    if (payload["threshRunning"].is<unsigned int>()) { 
        c.threshRunning = payload["threshRunning"]; 
        _pump->setThresholds(c.threshOff, c.threshNoWater, c.threshRunning, c.threshOverload);
        changed = true;
    }
    if (payload["threshOverload"].is<unsigned int>()) { 
        c.threshOverload = payload["threshOverload"]; 
        _pump->setThresholds(c.threshOff, c.threshNoWater, c.threshRunning, c.threshOverload);
        changed = true;
    }

    // Relay start mode
    if (payload["relayStartMode"].is<unsigned int>()) {
        int v = payload["relayStartMode"].as<int>();
        if (v >= 0 && v <= 2) { 
            c.relayStartMode = (RelayStartMode)v; 
            changed = true; 
        }
    }

    // Calibration coefficients
    if (payload["cCal"].is<double>()) { 
        c.cCal = payload["cCal"]; 
        _current->setCurrentMultiplier(c.cCal); 
        changed = true; 
    }
    if (payload["vCal"].is<double>()) { 
        c.vCal = payload["vCal"]; 
        _current->setVoltageMultiplier(c.vCal); 
        changed = true; }
    if (payload["pCal"].is<double>()) { 
        c.pCal = payload["pCal"]; 
        _current->setPowerMultiplier(c.pCal); 
        changed = true; 
    }


    // Debug network settings
    auto parseIP = [](const char* s, uint8_t ip[4]) -> bool {
        return sscanf(s, "%hhu.%hhu.%hhu.%hhu", &ip[0], &ip[1], &ip[2], &ip[3]) == 4;
        };
    if (payload["debugIp"].is<const char*>()) { 
        changed |= parseIP(payload["debugIp"], c.debugIp); 
        needReboot = true;
    }
    if (payload["debugGateway"].is<const char*>()) { 
        changed |= parseIP(payload["debugGateway"], c.debugGateway); 
        needReboot = true;
    }
    if (payload["debugNetmask"].is<const char*>()) { 
        changed |= parseIP(payload["debugNetmask"], c.debugNetmask); 
        needReboot = true; 
    }

    // System log settings
    if (payload["sysLogFileEnabled"].is<bool>()) {
        c.sysLogFileEnabled = payload["sysLogFileEnabled"];
        _log->setSysLogFileEnabled(c.sysLogFileEnabled);
        changed = true;
    }
    if (payload["sysLogFileLevel"].is<unsigned int>()) {
        c.sysLogFileLevel = payload["sysLogFileLevel"];
        _log->setSysLogFileLevel(c.sysLogFileLevel);
        changed = true;
    }

    if (changed) {
        _cfg->save(c);
        resp["status"] = "ok";
        resp["message"] = needReboot ? "Config saved. Reboot required." : "Config saved.";
        resp["needReboot"] = needReboot;
        LT_IM(CMD, "Config updated%s", needReboot ? " (reboot needed)" : "");
    }
    else {
        resp["status"] = "ok";
        resp["message"] = "No changes";
    }

    _sendResponse(source, resp);
}


void CommandHandler::_cmdGetLog(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    String logContent;
    if (_log->readSysLog(logContent, 4096)) {
        resp["status"] = "ok";
        resp["log"] = logContent;
        resp["logSize"] = _log->getSysLogSize();
    }
    else {
        resp["status"] = "error";
        resp["message"] = "No log available";
    }
    _sendResponse(source, resp);
}

void CommandHandler::_cmdClearSysLog(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _log->clearSysLog();
    resp["status"] = "ok";
    resp["message"] = "Sys log cleared";
    _sendResponse(source, resp);
}

void CommandHandler::_cmdOtaUrl(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    const char* url = payload["url"];
    if (!url) {
        resp["status"] = "error";
        resp["message"] = "Missing URL";
        _sendResponse(source, resp);
        return;
    }
    resp["status"] = "ok";
    resp["message"] = "OTA started from URL";
    _sendResponse(source, resp);

    static int lastPct = -1;
    lastPct = -1;

    _ota->startFromUrl(url,
        [this, source](int progress, int total) {
            int pct = (total > 0) ? (progress * 100 / total) : 0;
            // Only send log every 5% to avoid flooding WebSocket
            if (pct >= lastPct + 5 || pct == 100 || (pct == 0 && pct != lastPct)) {
                lastPct = pct;
                LT_IM(OTA, "OTA Progress: %d%%", pct);
            }
        },
        [this, source](bool success, const char* msg) {
            LT_IM(OTA, "OTA upload %s: %s", success ? "success" : "failed", msg);
        }
    );
}

void CommandHandler::_cmdReboot(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp["status"] = "ok";
    resp["message"] = "Rebooting...";
    _sendResponse(source, resp);
    LT_IM(CMD, "Rebooting...");
    delay(1000);
    ESP.restart();
}

void CommandHandler::_cmdFactoryReset(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _cfg->reset();
    resp["status"] = "ok";
    resp["message"] = "Factory reset. Rebooting...";
    _sendResponse(source, resp);
    LT_IM(CMD, "Factory reset");
    delay(1000);
    ESP.restart();
}

void CommandHandler::_cmdCalibrate(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    bool didCalib = false;

    if (payload["current"].is<double>()) {
        double expected = payload["current"].as<double>();
        _current->calibrateCurrent(expected);
        LT_IM(CMD, "Calibrated current to %.2fA", expected);
        didCalib = true;
    }

    if (payload["voltage"].is<unsigned int>()) {
        unsigned int expected = payload["voltage"].as<unsigned int>();
        _current->calibrateVoltage(expected);
        LT_IM(CMD, "Calibrated voltage to %u V", expected);
        didCalib = true;
    }

    if (payload["power"].is<unsigned int>()) {
        unsigned int expected = payload["power"].as<unsigned int>();
        _current->calibratePower(expected);
        LT_IM(CMD, "Calibrated power to %u W", expected);
        didCalib = true;
    }

    if (didCalib) {
        DeviceConfig& c = _cfg->get();
        c.cCal = _current->getCurrentMultiplier();
        c.vCal = _current->getVoltageMultiplier();
        c.pCal = _current->getPowerMultiplier();
        _cfg->save(c);
        resp["status"] = "ok";
        resp["message"] = "Calibrated";
    }

    resp["cCal"] = _current->getCurrentMultiplier();
    resp["vCal"] = _current->getVoltageMultiplier();
    resp["pCal"] = _current->getPowerMultiplier();
    _sendResponse(source, resp);
}

void CommandHandler::_cmdResetCalibration(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _current->resetCalibration();
    DeviceConfig& c = _cfg->get();
    c.cCal = _current->getCurrentMultiplier();
    c.vCal = _current->getVoltageMultiplier();
    c.pCal = _current->getPowerMultiplier();
    _cfg->save(c);
    resp["status"] = "ok";
    resp["message"] = "Calibration reset to HW defaults";
    resp["cCal"] = c.cCal;
    resp["vCal"] = c.vCal;
    resp["pCal"] = c.pCal;
    _sendResponse(source, resp);
    LT_IM(CMD, "Calibration reset");
}

void CommandHandler::_cmdSetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    extern void setLogMqttEnable(bool);
    bool en = payload["enabled"].as<bool>();
    setLogMqttEnable(en);
    resp["status"] = "ok";
    resp["enabled"] = en;
    _sendResponse(source, resp);
}

void CommandHandler::_cmdGetLogMqtt(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    extern bool isLogMqttEnabled();
    resp["status"] = "ok";
    resp["enabled"] = isLogMqttEnabled();
    _sendResponse(source, resp);
}


void CommandHandler::_cmdGetLogStats(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    resp["status"] = "ok";
    resp["sysLogSize"] = _log->getSysLogSize();
    resp["toggleLogSize"] = _log->getToggleLogSize();
    resp["powerLogSize"] = _log->getPowerLogSize();
    resp["totalBytes"] = _log->getTotalBytes();
    resp["usedBytes"] = _log->getUsedBytes();
    resp["timeSynced"] = _log->isTimeSynced();
    _sendResponse(source, resp);
}

void CommandHandler::_cmdUploadFirmwareStart(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (!payload["size"].is<unsigned int>()) {
        resp["status"] = "error";
        resp["message"] = "Missing size";
        _sendResponse(source, resp);
        return;
    }
    size_t size = payload["size"].as<unsigned int>();
    bool isMqtt = (source == "mqtt");

    static int lastPct = -1;
    lastPct = -1;
    bool ok = _ota->startFromStream(size,
        [this, source, isMqtt](int progress, int total) {
            int pct = (total > 0) ? (progress * 100 / total) : 0;
            // Only send log every 5% to avoid flooding WebSocket
            if (pct >= lastPct + 5 || pct == 100 || (pct == 0 && pct != lastPct)) {
                lastPct = pct;
                LT_IM(OTA, "OTA Progress: %d%%", pct);
                if (isMqtt) {
                    JsonDocument progResp;
                    progResp["cmd"] = "otaProgress";
                    progResp["status"] = "ok";
                    progResp["progress"] = progress;
                    progResp["total"] = total;
                    progResp["pct"] = pct;
                    _sendResponse(source, progResp);
                }
            }
        },
        [this, source](bool success, const char* msg) {
            LT_IM(OTA, "OTA upload %s: %s", success ? "success" : "failed", msg);
            JsonDocument resultResp;
            resultResp["cmd"] = "otaResult";
            resultResp["status"] = success ? "ok" : "error";
            resultResp["message"] = msg;
            _sendResponse(source, resultResp);
        });
    if (ok) {
        resp["status"] = "ok";
        resp["cmd"] = "beginUploadFirmwareSuccess";
        LT_IM(OTA, "stream started, size=%u", size);
    }
    else {
        resp["status"] = "error";
        resp["cmd"] = "beginUploadFirmwareFailed";
        resp["message"] = "OTA already running or update begin failed";
        LT_IM(OTA, "stream start failed");
    }
    _sendResponse(source, resp);
}

void CommandHandler::_cmdUploadFirmwareEnd(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    if (!_ota->isRunning()) {
        resp["status"] = "error";
        resp["message"] = "No OTA in progress";
        _sendResponse(source, resp);
        return;
    }
    _ota->end();
    LT_IM(OTA, "stream ended");
}

void CommandHandler::_cmdUploadFirmwareAbort(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    (void)resp;
    if (!_ota->isRunning()) {
        LT_IM(OTA, "abort requested but not running");
        return;
    }
    _ota->abort();
    LT_IM(OTA, "aborted");
}

void CommandHandler::_cmdOtaChunk(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    if (!_ota->isRunning()) {
        resp["status"] = "error";
        resp["message"] = "No OTA in progress";
        _sendResponse(source, resp);
        return;
    }
    String b64 = payload["data"].as<String>();
    if (b64.length() == 0) {
        resp["status"] = "error";
        resp["message"] = "Missing data";
        _sendResponse(source, resp);
        return;
    }
    size_t decodedMax = (b64.length() * 3) / 4;
    uint8_t* buf = new uint8_t[decodedMax];
    size_t olen;
    int r = mbedtls_base64_decode(buf, decodedMax, &olen,
        (const unsigned char*)b64.c_str(), b64.length());
    if (r != 0) {
        delete[] buf;
        resp["status"] = "error";
        resp["message"] = "Base64 decode failed";
        _sendResponse(source, resp);
        return;
    }
    bool ok = _ota->writeChunk(buf, olen);
    delete[] buf;
    if (!ok) {
        resp["status"] = "error";
        resp["message"] = "Write chunk failed";
    }
    else {
        resp["status"] = "ok";
        resp["received"] = (unsigned long)olen;
    }
    //_sendResponse(source, resp);
}

void CommandHandler::_cmdGetSystemInfo(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    resp["status"] = "ok";

    JsonVariantConst fields = payload["fields"];
    bool all = fields.isNull() || (fields.is<const char*>() && strcmp(fields.as<const char*>(), "all") == 0);

    auto has = [&](const char* name) -> bool {
        if (all) return true;
        for (auto f : fields.as<JsonArrayConst>()) {
            if (strcmp(f.as<const char*>(), name) == 0) return true;
        }
        return false;
        };

    if (has("system")) {
        JsonObject sys = resp["system"].to<JsonObject>();
        sys["chipId"] = ESP.getChipId();
        sys["chipModel"] = "LN882H";
        sys["cpuFreq"] = ESP.getCpuFreqMHz();
        sys["sdkVersion"] = ESP.getSdkVersion();
        sys["firmwareVersion"] = FIRMWARE_VERSION;
        sys["buildTime"] = buildStr();
        sys["buildUnixTime"] = buildUnixTime();
        sys["uptime"] = millis() / 1000;

        time_t raw = _log->getEpoch();
        struct tm ti;
        gmtime_r(&raw, &ti);
        char buf[26];
        snprintf(buf, sizeof(buf), "%02d-%02d-%04d %02d:%02d:%02d", ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900, ti.tm_hour, ti.tm_min, ti.tm_sec);
        sys["timeSys"] = String(buf);
        sys["resetReason"] = ESP.getResetReason();
        
    }

    if (has("memory")) {
        JsonObject mem = resp["memory"].to<JsonObject>();
        mem["freeHeap"] = ESP.getFreeHeap();
        mem["minEverFreeHeap"] = lt_heap_get_min_free();
    }

    if (has("tasks")) {
        JsonArray tasks = resp["tasks"].to<JsonArray>();
        UBaseType_t numTasks = uxTaskGetNumberOfTasks();
        TaskStatus_t* taskArray = (TaskStatus_t*)pvPortMalloc(numTasks * sizeof(TaskStatus_t));
        if (taskArray) {
            UBaseType_t count = uxTaskGetSystemState(taskArray, numTasks, nullptr);
            for (UBaseType_t i = 0; i < count; i++) {
                JsonObject t = tasks.add<JsonObject>();
                t["name"] = taskArray[i].pcTaskName;
                t["priority"] = taskArray[i].uxCurrentPriority;
                t["stackWaterMark"] = taskArray[i].usStackHighWaterMark;
                const char* stateStr = "other";
                switch (taskArray[i].eCurrentState) {
                case eRunning:   stateStr = "running"; break;
                case eReady:     stateStr = "ready"; break;
                case eBlocked:   stateStr = "blocked"; break;
                case eSuspended: stateStr = "suspended"; break;
                default: break;
                }
                t["state"] = (const char*)stateStr;
            }
            vPortFree(taskArray);
        }
    }

    if (has("wifi")) {
        JsonObject w = resp["wifi"].to<JsonObject>();
        w["rssi"] = WiFi.RSSI();
        w["ssid"] = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPSSID() : WiFi.SSID();
        {
            IPAddress ip = (WiFi.getMode() == WIFI_AP) ? WiFi.softAPIP() : WiFi.localIP();
            char ipBuf[16];
            snprintf(ipBuf, sizeof(ipBuf), "%d.%d.%d.%d", ip[0], ip[1], ip[2], ip[3]);
            w["ip"] = (const char*)ipBuf;
        }
        w["mac"] = WiFi.macAddress();
        w["channel"] = WiFi.channel();
        switch (g_connMode) {
        case ConnMode::AP_WS:     w["connMode"] = "ap_ws"; break;
        case ConnMode::STA_MQTT:  w["connMode"] = "sta_mqtt"; break;
        case ConnMode::DEBUG_WS:  w["connMode"] = "debug_ws"; break;
        }
        w["temperature"] = 25.0f + (cal_adc_read(ADC_CH0) - 770.0f) / 2.54f;
    }

    if (has("storage")) {
        JsonObject s = resp["storage"].to<JsonObject>();
        s["flashSize"] = ESP.getFlashChipSize();
        s["fsTotal"] = (unsigned long)LITTLEFS.totalBytes();
        s["fsUsed"] = (unsigned long)LITTLEFS.usedBytes();
    }

    if (has("pump")) {
        JsonObject p = resp["pump"].to<JsonObject>();
        p["relay"] = _pump->isOn();
        p["voltage"] = _current->getVoltage();
        p["current"] = _current->getCurrent();
        p["power"] = _current->getActivePower();
        p["apparent"] = _current->getApparentPower();
        p["dailyEnergy"] = _current->getDailyEnergy();
        p["hourlyEnergy"] = _current->getHourlyEnergy();
        p["temperature"] = _temp->readCelsius();
        switch (_pump->getState()) {
        case PumpState::OFF:        p["pumpState"] = "off"; break;
        case PumpState::RUNNING_OK: p["pumpState"] = "running"; break;
        case PumpState::DRY_RUN:       p["pumpState"] = "dry_run"; break;
        case PumpState::HIGH_CURRENT:  p["pumpState"] = "high_current"; break;
        case PumpState::CRITICAL_CURRENT: p["pumpState"] = "critical_current"; break;
        case PumpState::OVERLOAD:      p["pumpState"] = "overload"; break;
        }
    }

    _sendResponse(source, resp);

    if (payload["stream"].is<bool>() && payload["stream"].as<bool>()) {
        startStream(STREAM_SYSINFO, source, STREAM_DURATION_MS);
    }
}

void CommandHandler::_cmdClearPumpFault(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    _pump->clearPumpFault();
    resp["status"] = "ok";
    resp["message"] = "Pump fault cleared";
    LT_IM(CMD, "Clear pump fault");
    _sendResponse(source, resp);
}

void CommandHandler::_cmdScanWifi(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;

    if (_scanPending) {
        resp["status"] = "error";
        resp["message"] = "Scan already in progress";
        _sendResponse(source, resp);
        return;
    }

    _scanPending = true;
    _scanSource = source;

    if (!_scanEventHandlerId) {
        LT_IM(CMD, "Registering WiFi scan event handler");
        _scanEventHandlerId = WiFi.onEvent([this](EventId event, EventInfo info) {
            (void)event;
            (void)info;
            _scanPending = false;

            JsonDocument doc;
            doc["cmd"] = "scanWifi";

            int16_t count = WiFi.scanComplete();
            if (count >= 0) {
                ln_list_t *list = NULL;
                uint8_t apCount = 0;
                wifi_manager_get_ap_list(&list, &apCount);
                JsonArray nets = doc["networks"].to<JsonArray>();
                int idx = 0;
                ap_info_node_t *pnode;
                LN_LIST_FOR_EACH_ENTRY(pnode, ap_info_node_t, list, list) {
                    if (idx >= count) break;
                    ap_info_t *ap = &pnode->info;
                    JsonObject n = nets.add<JsonObject>();
                    n["name"] = ap->ssid;
                    n["rssi"] = (int32_t)ap->rssi;
                    char bssid[18];
                    snprintf(bssid, sizeof(bssid), "%02X:%02X:%02X:%02X:%02X:%02X",
                             ap->bssid[0], ap->bssid[1], ap->bssid[2],
                             ap->bssid[3], ap->bssid[4], ap->bssid[5]);
                    n["bssid"] = bssid;
                    n["isEncrypt"] = (ap->authmode != 0);
                    idx++;
                }
                doc["status"] = "ok";
            } else {
                doc["status"] = "error";
                doc["message"] = "Scan failed";
            }

            serializeJson(doc, _scanResultJson);
            _scanResultReady = true;
            WiFi.scanDelete();

            JsonDocument notify;
            notify["cmd"] = "scanWifi";
            notify["status"] = "completed";
            _sendResponse(_scanSource, notify);
        }, ARDUINO_EVENT_WIFI_SCAN_DONE);
    }

    LT_IM(CMD, "Starting async WiFi scan (raw SDK RSSI)...");
    WiFi.scanDelete();
    WiFi.scanNetworks(true, false, false, 200);

    resp["status"] = "ok";
    resp["message"] = "Scan started";
    _sendResponse(source, resp);
}

void CommandHandler::_cmdGetScanWifiData(const String& source, const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;

    if (_scanPending) {
        resp["status"] = "error";
        resp["message"] = "Scan still in progress";
        _sendResponse(source, resp);
        return;
    }

    if (!_scanResultReady) {
        resp["status"] = "error";
        resp["message"] = "No scan data available";
        _sendResponse(source, resp);
        return;
    }

    _scanResultReady = false;
    _sendResponse(source, _scanResultJson);
    _scanResultJson = "";
}

void CommandHandler::_handleFileCommand(const String& source, const String& cmd, const JsonDocument& payload, const String& reqId) {
    String path = payload["path"] | String("/");

    String json;
    if (cmd == "listDir") {
        size_t offset = payload["offset"] | (unsigned int)0;
        size_t limit = payload["limit"] | (unsigned int)0;
        json = FileBrowser::listDir(path, offset, limit);
    }
    else if (cmd == "fileInfo") {
        json = FileBrowser::fileInfo(path);
    }
    else if (cmd == "deleteItem") {
        json = FileBrowser::deleteItem(path);
    }
    else if (cmd == "fsInfo") {
        json = FileBrowser::fsInfo();
    }
    else if (cmd == "downloadFile" || cmd == "readFile") {
        size_t offset = payload["offset"] | (unsigned int)0;
        size_t limit = payload["limit"] | (unsigned int)(cmd == "downloadFile" ? 1024 : 4096);
        bool encode = payload["encode"] | (cmd == "downloadFile");
        json = FileBrowser::readFile(path, offset, limit, encode);
    }

    if (json.length() > 1 && json[0] == '{') {
        json = "{\"cmd\":\"" + cmd + "\"" + (reqId.length() > 0 ? ",\"reqId\":\"" + reqId + "\"" : "") + "," + json.substring(1);
    }
    _sendResponse(source, json);
}
