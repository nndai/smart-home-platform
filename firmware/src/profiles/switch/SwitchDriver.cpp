#include "profiles/switch/SwitchDriver.h"
#include "compat/kv.h"

void SwitchDriver::begin(DeviceConfig& cfg, ConfigSaveFn saveFn) {
    (void)saveFn;
    _cfg = &cfg;

    _relay.begin(PIN_RELAY, -1);  // không có triac

    if (cfg.relayStartMode == RelayStartMode::ON) {
        _relay.turnOn();
    }
    else if (cfg.relayStartMode == RelayStartMode::LAST) {
        char v[2] = {0};
        if (compat::kvGet("relay_state", v, sizeof(v), nullptr) == 0 && v[0] == '1') {
            _relay.turnOn();
        }
    }
}

void SwitchDriver::loop(uint32_t nowMs) {
    (void)nowMs;
    _relay.handle();
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
    resp["relay"] = _relay.getState();
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
    compat::kvSet("relay_state", _relay.getState() ? "1" : "0", 1);
}
