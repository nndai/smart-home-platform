#include "RemoteSwitchDriver.h"
#include <Arduino.h>
#include "core/Crypto.h"


void RemoteSwitchDriver::setServices(const DriverServices& svc) {
    _services = svc;
    
    _menuSteps[0] = { "Reset WiFi", [this]() { _menuResetWiFi(); } };
    _menuSteps[1] = { "DEBUG mode", [this]() { _menuDebugMode(); } };
    _menuSteps[2] = { "Factory reset", [this]() { _menuFactoryReset(); } };
}

void RemoteSwitchDriver::begin(DeviceConfig& cfg, ConfigSaveFn saveFn) {
    _cfg = static_cast<RemoteSwitchConfig*>(&cfg);
    _saveCb = saveFn;

    _ledConn.begin(PIN_LED_DISCONNECT, false); // Active HIGH
    _ledStatus.begin(PIN_LED_STATUS, false); // Active HIGH
    _ledError.begin(PIN_LED_ERROR, false); // Active HIGH

    _ledConn.on(); // Default disconnected
    _ledError.off();
    _button.setup(PIN_BUTTON, INPUT_PULLUP, true); // Active LOW
    _button.setPressMs(5000); // 5s long press
    _button.attachClick([](void* p) { static_cast<RemoteSwitchDriver*>(p)->_onButtonClick(); }, this);
    _button.attachDoubleClick([](void* p) { static_cast<RemoteSwitchDriver*>(p)->_onButtonDoubleClick(); }, this);
    _button.attachLongPressStart([](void* p) { static_cast<RemoteSwitchDriver*>(p)->_onButtonLongPressStart(); }, this);
    _menu.begin(&_ledStatus, _menuSteps, 3, 5000, 3000);
}

void RemoteSwitchDriver::loop(uint32_t nowMs) {
    (void)nowMs; // We use OneButton's tick which uses millis() internally
    
    _button.tick();
    _ledConn.update();
    _ledStatus.update();
    _ledError.update();
    _menu.tick(_button.debouncedValue());
    
    // Update simple LEDs
    updateLeds();
}

void RemoteSwitchDriver::updateLeds() {
    _connected = _services.isConnected ? _services.isConnected() : false;

    // LED 1 (Red): ON if disconnected
    if (!_connected) {
        _ledConn.on();
        _ledStatus.off();
        _ledError.off();
        return;
    }
    
    _ledConn.off();
    
    // LED 3 (Red): ON if targetError
    if (_targetError) {
        _ledError.on();
    } else {
        _ledError.off();
    }
    
    // Don't interfere with LED if menu is active
    if (_menu.isActive()) return;

    // LED 2 (Green): Blinking if targetOn, Solid if targetOff
    if (_targetOn) {
        _ledStatus.blink(500);
    } else {
        _ledStatus.on();
    }
}

bool RemoteSwitchDriver::buildEnvelope(const char* cmd, const JsonDocument& payload, JsonDocument& envelope) {
    if (_cfg->targetKey[0] == '\0') return false;

    // Use current time as sequence if available, else monotonic counter
    uint32_t seq = _targetSeq++;
    if (_services.log && _services.log->isTimeSynced()) {
        seq = _services.log->getEpoch(); // Just use epoch as seq, or epoch * 1000
    }
    uint32_t ts = _services.log ? _services.log->getEpoch() : 0;

    String payloadStr;
    serializeJson(payload, payloadStr);

    String canonical = String(seq) + "|" + String(ts) + "|" + String(cmd) + "|" + payloadStr;

    char hmacHex[65];
    if (!crypto::hmacSha256HexKey(_cfg->targetKey, canonical.c_str(), canonical.length(), hmacHex)) {
        return false;
    }

    envelope["cmd"] = cmd;
    envelope["seq"] = seq;
    envelope["ts"] = ts;
    envelope["hmac"] = hmacHex;
    envelope["payload"] = payload;

    return true;
}

void RemoteSwitchDriver::sendToggleCommand() {
    if (_cfg->targetId[0] == '\0') return;

    JsonDocument payload;
    // We send 'toggle' or 'setRelay' with opposite state
    payload["state"] = !_targetOn; 

    JsonDocument envelope;
    if (buildEnvelope(strcmp(_cfg->targetType, "pump") == 0 ? "pumpCtrl" : "setRelay", payload, envelope)) {
        // Send via MQTT. main.cpp will route this!
        String json;
        serializeJson(envelope, json);
        
        // Use a special prefix for the core to route it, or let core provide mqttPublish
        // For now, we will add a custom topic publisher to DriverServices in main.cpp
        if (_services.log) {
            LT_IM(CMD, "Sending command to %s", _cfg->targetId);
        }
        
        // We will call sendResponse with a special marker so main.cpp routes it
        if (_services.sendResponse) {
            _services.sendResponse("route:" + String(_cfg->targetId) + "/down|" + json);
        }
    }
}

void RemoteSwitchDriver::handleTargetStatus(const JsonDocument& doc) {
    // This is called when we receive a message from myhome/targetId/up
    // It's the public status of the target device
    String status = doc["status"].as<String>();
    
    if (doc["relay"].is<bool>()) {
        _targetOn = doc["relay"].as<bool>();
    } else if (doc["state"].is<const char*>()) {
        String st = doc["state"].as<String>();
        if (st == "on") _targetOn = true;
        else if (st == "off") _targetOn = false;
    }
    
    // Check error state (for pump)
    _targetError = false;
    if (doc["pump"].is<JsonObject>()) {
        JsonObjectConst p = doc["pump"].as<JsonObjectConst>();
        if (p["state"] == "dry_run" || p["state"] == "overload") {
            _targetError = true;
        }
    }
}

bool RemoteSwitchDriver::handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) {
    if (strcmp(cmd, "setTarget") == 0) {
        if (!payload["targetId"].is<const char*>() || !payload["targetKey"].is<const char*>()) {
            resp["status"] = "error";
            resp["message"] = "Missing targetId or targetKey";
            return true;
        }
        strncpy(_cfg->targetId, payload["targetId"].as<const char*>(), sizeof(_cfg->targetId) - 1);
        strncpy(_cfg->targetKey, payload["targetKey"].as<const char*>(), sizeof(_cfg->targetKey) - 1);
        
        if (payload["targetType"].is<const char*>()) {
            strncpy(_cfg->targetType, payload["targetType"].as<const char*>(), sizeof(_cfg->targetType) - 1);
        } else {
            strcpy(_cfg->targetType, "pump"); // Default
        }
        
        if (_saveCb) _saveCb();
        
        resp["status"] = "ok";
        return true;
    }
    return false;
}

void RemoteSwitchDriver::getStatus(JsonDocument& resp) {
    resp["targetId"] = _cfg->targetId;
    resp["targetType"] = _cfg->targetType;
}

void RemoteSwitchDriver::getConfig(JsonDocument& resp) {
    resp["targetId"] = _cfg->targetId;
}

bool RemoteSwitchDriver::setConfig(const JsonDocument& payload, JsonDocument& resp) {
    (void)payload;
    (void)resp;
    return false; // Target configuration is handled by handleCmd(setTarget) for security
}

// ── Nút nhấn và Menu ──

void RemoteSwitchDriver::_onButtonClick() {
    LT_IM(BTN, "Button click");
    if (_cfg->targetId[0] != '\0') {
        sendToggleCommand();
    }
}

void RemoteSwitchDriver::_onButtonDoubleClick() {
    LT_IM(BTN, "Button double click");
}

void RemoteSwitchDriver::_onButtonLongPressStart() {
    LT_IM(BTN, "Button long press start");
    _menu.start();
}

void RemoteSwitchDriver::_menuResetWiFi() {
    LT_IM(BTN, "Button long press: Reset WiFi");
    _cfg->connMode = ConnMode::AP_WS;
    if (_saveCb) _saveCb();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}

void RemoteSwitchDriver::_menuDebugMode() {
    LT_IM(BTN, "Button long press: Enter DEBUG mode");
    _cfg->connMode = ConnMode::DEBUG_WS;
    if (_saveCb) _saveCb();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}

void RemoteSwitchDriver::_menuFactoryReset() {
    LT_IM(BTN, "Button long press: Factory reset");
    if (_services.resetConfig) _services.resetConfig();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}
