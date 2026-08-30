#include "profiles/pump/PumpDriver.h"
#include "protocol/BinaryProtocol.h"
#include "protocol/BinaryWriter.h"
#include "protocol/BinaryCommandIds.h"
#include "protocol/BinaryFieldIds.h"

void PumpDriver::setServices(const DriverServices& svc) {
    _log = svc.log;
    _saveConfig = svc.saveConfig;
    _resetConfig = svc.resetConfig;
    _sendResponse = svc.sendResponse;
    _sendBinaryResponse = svc.sendBinaryResponse;
    _publishStatus = svc.publishStatus;

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
    float power = _current.getActivePower();
    _pump.update(current, power);
}

bool PumpDriver::handleCmd(const char* cmd, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    if (strcmp(cmd, "setRelay") == 0) {
        bool on = false;
        if (!payload.getBool(protocol::FieldId::State, on)) {
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Missing or invalid 'state' field");
            return true;
        }
        setRelay(on);
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::State, on ? "on" : "off");
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
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::Message, "Calibration reset to HW defaults");
        resp.setDouble(protocol::FieldId::CCal, _cfg->cCal);
        resp.setDouble(protocol::FieldId::VCal, _cfg->vCal);
        resp.setDouble(protocol::FieldId::PCal, _cfg->pCal);
        LT_IM(CMD, "Calibration reset");
        return true;
    }
    if (strcmp(cmd, "clearPumpFault") == 0) {
        _pump.clearPumpFault();
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::Message, "Pump fault cleared");
        LT_IM(CMD, "Clear pump fault");
        return true;
    }
    return false;
}

void PumpDriver::getStatus(protocol::CommandResponse& resp) {
    BL0937SensorData blData = _current.readAll();

    resp.setBool(protocol::FieldId::Relay, _pump.isOn());
    resp.setU32(protocol::FieldId::OnDuration, _pump.isOn() ? (uint32_t)(_pump.getOnDuration() / 1000) : 0);
    resp.setFloat(protocol::FieldId::Current, blData.current);
    resp.setFloat(protocol::FieldId::Power, blData.power);
    resp.setFloat(protocol::FieldId::Voltage, blData.voltage);
    resp.setFloat(protocol::FieldId::DailyEnergy, blData.dailyEnergy);
    resp.setFloat(protocol::FieldId::HourlyEnergy, blData.hourlyEnergy);
    resp.setFloat(protocol::FieldId::Apparent, blData.apparent);
    resp.setFloat(protocol::FieldId::Pf, blData.pf);
    resp.setFloat(protocol::FieldId::Temperature, _temp.readCelsius());
    resp.setBool(protocol::FieldId::PumpMode, _cfg->pumpMode);
    switch (_pump.getState()) {
    case PumpState::OFF:
        resp.setString(protocol::FieldId::PumpStateStr, "OFF");
        resp.setI32(protocol::FieldId::PumpState, (int)PumpState::OFF);
        break;
    case PumpState::RUNNING_OK:
        resp.setString(protocol::FieldId::PumpStateStr, "RUNNING OK");
        resp.setI32(protocol::FieldId::PumpState, (int)PumpState::RUNNING_OK);
        break;
    case PumpState::DRY_RUN:
        resp.setString(protocol::FieldId::PumpStateStr, "DRY RUN");
        resp.setI32(protocol::FieldId::PumpState, (int)PumpState::DRY_RUN);
        break;
    case PumpState::HIGH_CURRENT:
        resp.setString(protocol::FieldId::PumpStateStr, "HIGH CURRENT");
        resp.setI32(protocol::FieldId::PumpState, (int)PumpState::HIGH_CURRENT);
        break;
    case PumpState::CRITICAL_CURRENT:
        resp.setString(protocol::FieldId::PumpStateStr, "CRITICAL CURRENT");
        resp.setI32(protocol::FieldId::PumpState, (int)PumpState::CRITICAL_CURRENT);
        break;
    case PumpState::OVERLOAD:
        resp.setString(protocol::FieldId::PumpStateStr, "OVERLOAD");
        resp.setI32(protocol::FieldId::PumpState, (int)PumpState::OVERLOAD);
        break;
    }
}

void PumpDriver::getConfig(protocol::CommandResponse& resp) {
    resp.setI32(protocol::FieldId::RelayStartMode, (int)_cfg->relayStartMode);
    resp.setBool(protocol::FieldId::PumpMode, _cfg->pumpMode);
    resp.setU32(protocol::FieldId::ThreshOff, _cfg->threshOff);
    resp.setU32(protocol::FieldId::ThreshNoWater, _cfg->threshNoWater);
    resp.setU32(protocol::FieldId::ThreshRunning, _cfg->threshRunning);
    resp.setU32(protocol::FieldId::ThreshOverload, _cfg->threshOverload);
    resp.setU32(protocol::FieldId::DryTimeout, _cfg->dryTimeout);
    resp.setU32(protocol::FieldId::OverloadTimeout, _cfg->overloadTimeout);
    resp.setDouble(protocol::FieldId::CCal, _cfg->cCal);
    resp.setDouble(protocol::FieldId::VCal, _cfg->vCal);
    resp.setDouble(protocol::FieldId::PCal, _cfg->pCal);
}

bool PumpDriver::setConfig(const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)resp;
    bool changed = false;

    uint32_t uv = 0;
    if (payload.getUint(protocol::FieldId::RelayStartMode, uv)) {
        if (uv <= 2) {
            _cfg->relayStartMode = (RelayStartMode)uv;
            changed = true;
        }
    }
    bool bv = false;
    if (payload.getBool(protocol::FieldId::PumpMode, bv)) {
        _cfg->pumpMode = bv;
        _pump.setPumpMode(_cfg->pumpMode);
        changed = true;
    }
    if (payload.getUint(protocol::FieldId::DryTimeout, uv)) {
        _cfg->dryTimeout = uv;
        _pump.setTimeouts(_cfg->dryTimeout, _cfg->overloadTimeout);
        changed = true;
    }
    if (payload.getUint(protocol::FieldId::OverloadTimeout, uv)) {
        _cfg->overloadTimeout = uv;
        _pump.setTimeouts(_cfg->dryTimeout, _cfg->overloadTimeout);
        changed = true;
    }
    if (payload.getUint(protocol::FieldId::ThreshOff, uv)) {
        _cfg->threshOff = uv;
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload.getUint(protocol::FieldId::ThreshNoWater, uv)) {
        _cfg->threshNoWater = uv;
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload.getUint(protocol::FieldId::ThreshRunning, uv)) {
        _cfg->threshRunning = uv;
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    if (payload.getUint(protocol::FieldId::ThreshOverload, uv)) {
        _cfg->threshOverload = uv;
        _pump.setThresholds(_cfg->threshOff, _cfg->threshNoWater, _cfg->threshRunning, _cfg->threshOverload);
        changed = true;
    }
    double dv = 0;
    if (payload.getDouble(protocol::FieldId::CCal, dv)) {
        _cfg->cCal = dv;
        _current.setCurrentMultiplier(_cfg->cCal);
        changed = true;
    }
    if (payload.getDouble(protocol::FieldId::VCal, dv)) {
        _cfg->vCal = dv;
        _current.setVoltageMultiplier(_cfg->vCal);
        changed = true;
    }
    if (payload.getDouble(protocol::FieldId::PCal, dv)) {
        _cfg->pCal = dv;
        _current.setPowerMultiplier(_cfg->pCal);
        changed = true;
    }
    return changed;
}

void PumpDriver::getSysInfo(protocol::CommandResponse& resp) {
    protocol::CommandResponse* p = resp.beginObject(protocol::FieldId::Pump);
    if (!p) return;
    p->setBool(protocol::FieldId::Relay, _pump.isOn());
    p->setU32(protocol::FieldId::OnDuration, _pump.isOn() ? (uint32_t)(_pump.getOnDuration() / 1000) : 0);
    p->setFloat(protocol::FieldId::Voltage, _current.getVoltage());
    p->setFloat(protocol::FieldId::Current, _current.getCurrent());
    p->setFloat(protocol::FieldId::Power, _current.getActivePower());
    p->setFloat(protocol::FieldId::Apparent, _current.getApparentPower());
    p->setFloat(protocol::FieldId::DailyEnergy, _current.getDailyEnergy());
    p->setFloat(protocol::FieldId::HourlyEnergy, _current.getHourlyEnergy());
    p->setFloat(protocol::FieldId::Temperature, _temp.readCelsius());
    switch (_pump.getState()) {
    case PumpState::OFF:
        p->setString(protocol::FieldId::PumpState, "off");
        break;
    case PumpState::RUNNING_OK:
        p->setString(protocol::FieldId::PumpState, "running");
        break;
    case PumpState::DRY_RUN:
        p->setString(protocol::FieldId::PumpState, "dry_run");
        break;
    case PumpState::HIGH_CURRENT:
        p->setString(protocol::FieldId::PumpState, "high_current");
        break;
    case PumpState::CRITICAL_CURRENT:
        p->setString(protocol::FieldId::PumpState, "critical_current");
        break;
    case PumpState::OVERLOAD:
        p->setString(protocol::FieldId::PumpState, "overload");
        break;
    }
    resp.endObject(p);
}

void PumpDriver::_handleCalibrate(const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    bool didCalib = false;

    double ev = 0;
    if (payload.getDouble(protocol::FieldId::Current, ev)) {
        _current.calibrateCurrent(ev);
        LT_IM(CMD, "Calibrated current to %.2fA", ev);
        didCalib = true;
    }
    float ef = 0;
    if (payload.getFloat(protocol::FieldId::Voltage, ef)) {
        _current.calibrateVoltage(ef);
        LT_IM(CMD, "Calibrated voltage to %.1f V", ef);
        didCalib = true;
    }
    if (payload.getFloat(protocol::FieldId::Power, ef)) {
        _current.calibratePower(ef);
        LT_IM(CMD, "Calibrated power to %.1f W", ef);
        didCalib = true;
    }

    if (didCalib) {
        _cfg->cCal = _current.getCurrentMultiplier();
        _cfg->vCal = _current.getVoltageMultiplier();
        _cfg->pCal = _current.getPowerMultiplier();
        if (_saveFn) _saveFn();
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::Message, "Calibrated");
    }
    resp.setDouble(protocol::FieldId::CCal, _current.getCurrentMultiplier());
    resp.setDouble(protocol::FieldId::VCal, _current.getVoltageMultiplier());
    resp.setDouble(protocol::FieldId::PCal, _current.getPowerMultiplier());
}

void PumpDriver::_onPumpState(PumpState state, float current, bool isOn, const char* msg) {
    (void)current;
    (void)msg;

    // Báo lỗi tức thì cho các thiết bị theo dõi (Remote Switch...) mà không cần
    // chờ stream: gửi 1 status ngay khi vào trạng thái lỗi.
    if (state == PumpState::DRY_RUN) {
        _led.blink(500);
        if (_publishStatus) _publishStatus();
    }
    else if (state == PumpState::OVERLOAD) {
        _led.blink(200);
        if (_publishStatus) _publishStatus();
    }
    else if (state == PumpState::HIGH_CURRENT) {
        _led.blink(2, 500, 3000);
        //if (_publishStatus) _publishStatus();
    }
    else if (state == PumpState::CRITICAL_CURRENT) {
        _led.blink(3, 200, 1000);
        if (_publishStatus) _publishStatus();
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

    protocol::BinaryWriter writer(protocol::CommandId::SetRelay);
    writer.writeString(protocol::FieldId::Status, "ok", 2);
    writer.writeString(protocol::FieldId::State, on ? "on" : "off", on ? 2 : 3);
    if (_sendBinaryResponse) {
        _sendBinaryResponse(writer.data(), writer.size());
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
