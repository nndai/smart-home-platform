#include "profiles/pump/PumpDriver.h"

void PumpDriver::setServices(const DriverServices& svc) {
    _log = svc.log;
    _saveConfig = svc.saveConfig;
    _resetConfig = svc.resetConfig;
    _sendResponse = svc.sendResponse;

    _menuSteps[0] = { "Reset WiFi", [this]() { _menuResetWiFi(); } };
    _menuSteps[1] = { "DEBUG mode", [this]() { _menuDebugMode(); } };
    _menuSteps[2] = { "Factory reset", [this]() { _menuFactoryReset(); } };
}

void PumpDriver::begin(DeviceConfig& cfg, ConfigSaveFn saveFn) {
    _cfg = static_cast<PumpConfig*>(&cfg);
    _saveFn = saveFn;
    PumpConfig& c = *_cfg;

    // ── UI: LED + nút nhấn (riêng của pump, tự quản trong driver) ──
    _led.begin(PIN_LED, LED_ACTIVE_LOW);
    _button.setup(PIN_BUTTON, INPUT_PULLUP, BUTTON_ACTIVE_LOW);
    _button.setPressMs(BUTTON_LONG_PRESS_MS);
    _button.attachClick([](void* p) { static_cast<PumpDriver*>(p)->_onButtonClick(); }, this);
    _button.attachDoubleClick([](void* p) { static_cast<PumpDriver*>(p)->_onButtonDoubleClick(); }, this);
    _button.attachLongPressStart([](void* p) { static_cast<PumpDriver*>(p)->_onButtonLongPressStart(); }, this);
    _menu.begin(&_led, _menuSteps, 3, BUTTON_LONG_PRESS_MS, BUTTON_CONFIRM_TIMEOUT_MS);

    _current.begin(PIN_BL0937_CF, PIN_BL0937_CF1, PIN_BL0937_SEL);
    if (isnan(c.cCal) || isnan(c.vCal) || isnan(c.pCal)) {
        c.cCal = _current.getCurrentMultiplier();
        c.vCal = _current.getVoltageMultiplier();
        c.pCal = _current.getPowerMultiplier();
        if (_saveFn) _saveFn();
    }
    else {
        _current.setCurrentMultiplier(c.cCal);
        _current.setVoltageMultiplier(c.vCal);
        _current.setPowerMultiplier(c.pCal);
    }

    _temp.begin(PIN_NTC_ADC);
    _switch.begin(PIN_RELAY, PIN_TRIAC_GATE, false, true);  // relay + triac, relay active HIGH, triac active LOW
    _pump.begin(&_switch, c.pumpMode);
    _pump.setThresholds(c.threshOff, c.threshNoWater, c.threshRunning, c.threshOverload);
    _pump.setTimeouts(c.dryTimeout, c.overloadTimeout);
    _pump.setEventCallback([this](PumpState state, float current, bool isOn, const char* msg) {
        _onPumpState(state, current, isOn, msg);
    });

    if (c.relayStartMode == RelayStartMode::ON) {
        _pump.turnOn();
    }
    else {
        // OFF / LAST (TODO)
        _pump.turnOff();
    }
}

void PumpDriver::loop(uint32_t nowMs) {
    _button.tick();
    _led.update();
    _menu.tick(_button.debouncedValue());

    if (nowMs - _lastSensorLoop >= 1000) {
        _current.loop();
        _lastSensorLoop = nowMs;
        _energyTick();
    }
    float current = _current.getCurrent();
    _pump.update(current);
}

bool PumpDriver::handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) {
    if (strcmp(cmd, "setRelay") == 0) {
        if (!payload["state"].is<bool>()) {
            resp["status"] = "error";
            resp["message"] = "Missing or invalid 'state' field";
            return true;
        }
        bool on = payload["state"].as<bool>();
        setRelay(on);
        resp["status"] = "ok";
        resp["state"] = on ? "on" : "off";
        LT_IM(CMD, "Relay %s", on ? "ON" : "OFF");
        if (_log) _log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
        return true;
    }
    if (strcmp(cmd, "calibrate") == 0) {
        _handleCalibrate(payload, resp);
        return true;
    }
    if (strcmp(cmd, "resetCalibration") == 0) {
        _current.resetCalibration();
        _cfg->cCal = _current.getCurrentMultiplier();
        _cfg->vCal = _current.getVoltageMultiplier();
        _cfg->pCal = _current.getPowerMultiplier();
        if (_saveFn) _saveFn();
        resp["status"] = "ok";
        resp["message"] = "Calibration reset to HW defaults";
        resp["cCal"] = _cfg->cCal;
        resp["vCal"] = _cfg->vCal;
        resp["pCal"] = _cfg->pCal;
        LT_IM(CMD, "Calibration reset");
        return true;
    }
    if (strcmp(cmd, "clearPumpFault") == 0) {
        _pump.clearPumpFault();
        resp["status"] = "ok";
        resp["message"] = "Pump fault cleared";
        LT_IM(CMD, "Clear pump fault");
        return true;
    }
    return false;
}

void PumpDriver::getStatus(JsonDocument& resp) {
    BL0937SensorData blData = _current.readAll();

    resp["relay"] = _pump.isOn();
    resp["current"] = blData.current;
    resp["power"] = blData.power;
    resp["voltage"] = blData.voltage;
    resp["dailyEnergy"] = blData.dailyEnergy;
    resp["hourlyEnergy"] = blData.hourlyEnergy;
    resp["apparent"] = blData.apparent;
    resp["pf"] = blData.pf;
    resp["temperature"] = _temp.readCelsius();
    resp["pumpMode"] = _cfg->pumpMode;
    switch (_pump.getState()) {
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
}

void PumpDriver::getConfig(JsonDocument& resp) {
    resp["relayStartMode"] = (int)_cfg->relayStartMode;
    resp["pumpMode"] = _cfg->pumpMode;
    resp["threshOff"] = _cfg->threshOff;
    resp["threshNoWater"] = _cfg->threshNoWater;
    resp["threshRunning"] = _cfg->threshRunning;
    resp["threshOverload"] = _cfg->threshOverload;
    resp["dryTimeout"] = _cfg->dryTimeout;
    resp["overloadTimeout"] = _cfg->overloadTimeout;
    resp["cCal"] = _cfg->cCal;
    resp["vCal"] = _cfg->vCal;
    resp["pCal"] = _cfg->pCal;
}

bool PumpDriver::setConfig(const JsonDocument& payload, JsonDocument& resp) {
    (void)resp;
    bool changed = false;

    if (payload["relayStartMode"].is<unsigned int>()) {
        int v = payload["relayStartMode"].as<int>();
        if (v >= 0 && v <= 2) {
            _cfg->relayStartMode = (RelayStartMode)v;
            changed = true;
        }
    }
    if (payload["pumpMode"].is<bool>()) {
        _cfg->pumpMode = payload["pumpMode"].as<bool>();
        _pump.setPumpMode(_cfg->pumpMode);
        changed = true;
    }
    if (payload["dryTimeout"].is<unsigned int>()) {
        _cfg->dryTimeout = payload["dryTimeout"].as<unsigned int>();
        _pump.setTimeouts(_cfg->dryTimeout, _cfg->overloadTimeout);
        changed = true;
    }
    if (payload["overloadTimeout"].is<unsigned int>()) {
        _cfg->overloadTimeout = payload["overloadTimeout"].as<unsigned int>();
        _pump.setTimeouts(_cfg->dryTimeout, _cfg->overloadTimeout);
        changed = true;
    }
    if (payload["threshOff"].is<unsigned int>()) {
        _cfg->threshOff = payload["threshOff"].as<unsigned int>();
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload["threshNoWater"].is<unsigned int>()) {
        _cfg->threshNoWater = payload["threshNoWater"].as<unsigned int>();
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload["threshRunning"].is<unsigned int>()) {
        _cfg->threshRunning = payload["threshRunning"].as<unsigned int>();
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload["threshOverload"].is<unsigned int>()) {
        _cfg->threshOverload = payload["threshOverload"].as<unsigned int>();
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload["cCal"].is<double>()) {
        _cfg->cCal = payload["cCal"].as<double>();
        _current.setCurrentMultiplier(_cfg->cCal);
        changed = true;
    }
    if (payload["vCal"].is<double>()) {
        _cfg->vCal = payload["vCal"].as<double>();
        _current.setVoltageMultiplier(_cfg->vCal);
        changed = true;
    }
    if (payload["pCal"].is<double>()) {
        _cfg->pCal = payload["pCal"].as<double>();
        _current.setPowerMultiplier(_cfg->pCal);
        changed = true;
    }
    return changed;
}

void PumpDriver::getSysInfo(JsonDocument& resp) {
    JsonObject p = resp["pump"].to<JsonObject>();
    p["relay"] = _pump.isOn();
    p["voltage"] = _current.getVoltage();
    p["current"] = _current.getCurrent();
    p["power"] = _current.getActivePower();
    p["apparent"] = _current.getApparentPower();
    p["dailyEnergy"] = _current.getDailyEnergy();
    p["hourlyEnergy"] = _current.getHourlyEnergy();
    p["temperature"] = _temp.readCelsius();
    switch (_pump.getState()) {
    case PumpState::OFF:
        p["pumpState"] = "off";
        break;
    case PumpState::RUNNING_OK:
        p["pumpState"] = "running";
        break;
    case PumpState::DRY_RUN:
        p["pumpState"] = "dry_run";
        break;
    case PumpState::HIGH_CURRENT:
        p["pumpState"] = "high_current";
        break;
    case PumpState::CRITICAL_CURRENT:
        p["pumpState"] = "critical_current";
        break;
    case PumpState::OVERLOAD:
        p["pumpState"] = "overload";
        break;
    }
}

void PumpDriver::_handleCalibrate(const JsonDocument& payload, JsonDocument& resp) {
    bool didCalib = false;

    if (payload["current"].is<double>()) {
        double expected = payload["current"].as<double>();
        _current.calibrateCurrent(expected);
        LT_IM(CMD, "Calibrated current to %.2fA", expected);
        didCalib = true;
    }
    if (payload["voltage"].is<float>()) {
        float expected = payload["voltage"].as<float>();
        _current.calibrateVoltage(expected);
        LT_IM(CMD, "Calibrated voltage to %.1f V", expected);
        didCalib = true;
    }
    if (payload["power"].is<float>()) {
        float expected = payload["power"].as<float>();
        _current.calibratePower(expected);
        LT_IM(CMD, "Calibrated power to %.1f W", expected);
        didCalib = true;
    }

    if (didCalib) {
        _cfg->cCal = _current.getCurrentMultiplier();
        _cfg->vCal = _current.getVoltageMultiplier();
        _cfg->pCal = _current.getPowerMultiplier();
        if (_saveFn) _saveFn();
        resp["status"] = "ok";
        resp["message"] = "Calibrated";
    }
    resp["cCal"] = _current.getCurrentMultiplier();
    resp["vCal"] = _current.getVoltageMultiplier();
    resp["pCal"] = _current.getPowerMultiplier();
}

void PumpDriver::_onPumpState(PumpState state, float current, bool isOn, const char* msg) {
    (void)current;
    (void)msg;

    if (state == PumpState::DRY_RUN) {
        _led.blink(500);
    }
    else if (state == PumpState::OVERLOAD) {
        _led.blink(200);
    }
    else if (state == PumpState::HIGH_CURRENT) {
        _led.blink(2, 500, 3000);
    }
    else if (state == PumpState::CRITICAL_CURRENT) {
        _led.blink(3, 200, 1000);
    }
    else if (isOn == false) {
        _led.off();
    }
    else if (isOn == true) {
        _led.on();
    }
}

void PumpDriver::_energyTick() {
    uint32_t now = millis();
    if (_log && _log->isTimeSynced()) {
        uint32_t epoch = _log->getEpoch();
        uint32_t h = epoch / 3600;

        if (h != _lastHourEpoch && _lastHourEpoch != UINT32_MAX) {
            uint32_t wh = _current.getHourlyEnergy(); // Wh
            if (wh > 0) {
                uint8_t label = _lastHourEpoch % 24;
                time_t intervalStart = (time_t)_lastHourEpoch * 3600;
                _log->logHourlyPower(label, wh, intervalStart);
                _log->addTotalPower(wh);
            }
            _current.resetHourlyEnergy();
        }
        _lastHourEpoch = h;
        _hourRefMillis = now;

        uint32_t d = epoch / 86400;
        if (d != _lastDayEpoch && _lastDayEpoch != UINT32_MAX) {
            _current.resetDailyEnergy();
        }
        _lastDayEpoch = d;
    }
    else if (_log) {
        if (now - _hourRefMillis >= 3600000UL) {
            uint32_t wh = _current.getHourlyEnergy(); // Wh
            if (wh > 0) {
                _log->addTotalPower(wh);
            }
            _current.resetHourlyEnergy();
            _hourRefMillis = now;
        }
    }

    if (_log && now - _lastPumpTimeAdd >= 3600000UL) {
        _lastPumpTimeAdd = now;
        _log->addPumpTime(3600000UL);
    }
}

// ── Hành vi nút nhấn (riêng của pump) ──

void PumpDriver::_onButtonClick() {
    bool on = !_pump.isOn();
    setRelay(on);
    if (_log) _log->logToggle(LogManager::ToggleSource::TOGGLE_BUTTON, on);

    JsonDocument resp;
    resp["cmd"] = "setRelay";
    resp["status"] = "ok";
    resp["state"] = on ? "on" : "off";
    String json;
    serializeJson(resp, json);
    if (_sendResponse) {
        _sendResponse(json);
    }
}

void PumpDriver::_onButtonDoubleClick() {
    LT_IM(BTN, "Button double click");
}

void PumpDriver::_onButtonLongPressStart() {
    LT_IM(BTN, "Button long press start");
    _menu.start();
}

void PumpDriver::_menuResetWiFi() {
    LT_IM(BTN, "Button long press: Reset WiFi");
    _cfg->connMode = ConnMode::AP_WS;
    if (_saveConfig) _saveConfig();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}

void PumpDriver::_menuDebugMode() {
    LT_IM(BTN, "Button long press: Enter DEBUG mode");
    _cfg->connMode = ConnMode::DEBUG_WS;
    if (_saveConfig) _saveConfig();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}

void PumpDriver::_menuFactoryReset() {
    LT_IM(BTN, "Button long press: Factory reset");
    if (_resetConfig) _resetConfig();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}
