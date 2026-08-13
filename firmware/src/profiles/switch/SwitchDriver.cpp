#include "profiles/switch/SwitchDriver.h"
#include "compat/kv.h"

void SwitchDriver::begin(DeviceConfig& cfg, ConfigSaveFn saveFn) {
    (void)saveFn;
    _cfg = static_cast<SwitchConfig*>(&cfg);

    _switch.begin(PIN_RELAY, -1, PIN_RELAY_ACTIVE_LOW);  // relay only, no TRIAC

    if (_cfg->relayStartMode == RelayStartMode::ON) {
        _switch.turnOn();
    }
    else if (_cfg->relayStartMode == RelayStartMode::LAST) {
        char v[2] = {0};
        if (compat::kvGet("relay_state", v, sizeof(v), nullptr) == KvError::Ok && v[0] == '1') {
            _switch.turnOn();
        }
    }
}

void SwitchDriver::loop(uint32_t nowMs) {
    (void)nowMs;
    _switch.handle();
}

bool SwitchDriver::handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) {
    if (strcmp(cmd, "setRelay") == 0) {
        if (!payload["state"].is<bool>()) {
            resp["status"] = "error";
            resp["message"] = "Missing or invalid 'state' field";
            return true;
        }
        bool on = payload["state"].as<bool>();
        setRelay(on);
        _persistRelayState();
        resp["status"] = "ok";
        resp["state"] = on ? "on" : "off";
        LT_IM(CMD, "Relay %s", on ? "ON" : "OFF");
        if (_log) _log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
        return true;
    }
    return false;
}

void SwitchDriver::getStatus(JsonDocument& resp) {
    resp["relay"] = _switch.getState();
}

void SwitchDriver::getConfig(JsonDocument& resp) {
    resp["relayStartMode"] = (int)_cfg->relayStartMode;
}

bool SwitchDriver::setConfig(const JsonDocument& payload, JsonDocument& resp) {
    (void)resp;
    bool changed = false;

    if (payload["relayStartMode"].is<int>()) {
        _cfg->relayStartMode = (RelayStartMode)payload["relayStartMode"].as<int>();
        changed = true;
    }
    return changed;
}

void SwitchDriver::_persistRelayState() {
    if (!_cfg || _cfg->relayStartMode != RelayStartMode::LAST) return;
    compat::kvSet("relay_state", _switch.getState() ? "1" : "0", 1);
}
