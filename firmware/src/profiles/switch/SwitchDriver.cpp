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

bool SwitchDriver::handleCmd(const char* cmd, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    if (strcmp_P(cmd, PSTR("setRelay")) == 0) {
        bool on = false;
        if (!payload.getBool(protocol::FieldId::State, on)) {
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Missing or invalid 'state' field");
            return true;
        }
        setRelay(on);
        _persistRelayState();
        resp.setString(protocol::FieldId::Status, "ok");
        resp.setString(protocol::FieldId::State, on ? "on" : "off");
        LT_IM(CMD, "Relay %s", on ? "ON" : "OFF");
        if (_log) _log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
        return true;
    }
    return false;
}

void SwitchDriver::getStatus(protocol::CommandResponse& resp) {
    resp.setBool(protocol::FieldId::Relay, _switch.getState());
    resp.setU32(protocol::FieldId::OnDuration, _switch.getState() ? (uint32_t)(_switch.getOnDuration() / 1000) : 0);
}

void SwitchDriver::getConfig(protocol::CommandResponse& resp) {
    resp.setI32(protocol::FieldId::RelayStartMode, (int)_cfg->relayStartMode);
}

bool SwitchDriver::setConfig(const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)resp;
    bool changed = false;

    int32_t v = 0;
    if (payload.getInt(protocol::FieldId::RelayStartMode, v)) {
        _cfg->relayStartMode = (RelayStartMode)v;
        changed = true;
    }
    return changed;
}

void SwitchDriver::_persistRelayState() {
    if (!_cfg || _cfg->relayStartMode != RelayStartMode::LAST) return;
    compat::kvSet("relay_state", _switch.getState() ? "1" : "0", 1);
}
