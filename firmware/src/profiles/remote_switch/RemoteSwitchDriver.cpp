#include "RemoteSwitchDriver.h"
#include <Arduino.h>
#include "compat/wifi.h"
#include "core/Crypto.h"
#include "core/DeviceIdentity.h"
#include "protocol/BinaryProtocol.h"
#include "protocol/BinaryWriter.h"
#include "protocol/BinaryCommandIds.h"
#include "protocol/BinaryFieldIds.h"

void RemoteSwitchDriver::setServices(const DriverServices& svc) {
    _services = svc;

    _menuSteps[0] = { "Reset WiFi", [this]() { _menuResetWiFi(); } };
    _menuSteps[1] = { "DEBUG mode", [this]() { _menuDebugMode(); } };
    _menuSteps[2] = { "Factory reset", [this]() { _menuFactoryReset(); } };
    _menuSteps[3] = { "STA MQTT mode", [this]() { _staMqttMode(); } };
}

void RemoteSwitchDriver::begin(DeviceConfig& cfg, ConfigSaveFn saveFn) {
    _cfg = static_cast<RemoteSwitchConfig*>(&cfg);
    _saveCb = saveFn;

    _ledConnRed.begin(PIN_LED_CONNECT_RED, false);     // Active HIGH
    _ledConnGreen.begin(PIN_LED_CONNECT_GREEN, false); // Active HIGH
    _ledStateGreen.begin(PIN_LED_STATE_GREEN, false);  // Active HIGH
    _ledStateRed.begin(PIN_LED_STATE_RED, false);      // Active HIGH

    _button.setup(PIN_BUTTON, INPUT_PULLUP, BUTTON_ACTIVE_LOW);
    _button.setPressMs(BUTTON_LONG_PRESS_MS); // 5s long press
    _button.attachClick([](void* p) { static_cast<RemoteSwitchDriver*>(p)->_onButtonClick(); }, this);
    _button.attachDoubleClick([](void* p) { static_cast<RemoteSwitchDriver*>(p)->_onButtonDoubleClick(); }, this);
    _button.attachLongPressStart([](void* p) { static_cast<RemoteSwitchDriver*>(p)->_onButtonLongPressStart(); }, this);
    _menu.begin(&_ledStateGreen, _menuSteps, 4, BUTTON_LONG_PRESS_MS, BUTTON_CONFIRM_TIMEOUT_MS);

    // Bắt đầu đếm timeout status kể từ lúc boot (targetId có thể chưa cấu hình)
    _lastStatusRxMs = millis();

    subscribeTargetTopic();
}

void RemoteSwitchDriver::loop(uint32_t nowMs) {
    _button.tick();
    _ledConnRed.update();
    _ledConnGreen.update();
    _ledStateGreen.update();
    _ledStateRed.update();
    _menu.tick(_button.debouncedValue());

    // State machine timers (bỏ qua khi menu active để đèn menu không bị giật)
    if (!_menu.isActive()) {
        if (_visualState == TargetVisualState::WAITING && nowMs - _waitStartMs >= WAIT_RESPONSE_MS) {
            enterVisualState(TargetVisualState::ERROR, nowMs);
        }
        else if (_visualState == TargetVisualState::ERROR && nowMs - _errorStartMs >= ERROR_AUTOOFF_MS) {
            enterVisualState(TargetVisualState::OFF, nowMs);
        }
    }

    updateLeds(nowMs);
}

void RemoteSwitchDriver::updateLeds(uint32_t nowMs) {

    // Don't interfere with LED if menu is active
    if (_menu.isActive()) return;

    updateConnectLeds(nowMs);
    updateStateLeds();
}

void RemoteSwitchDriver::updateConnectLeds(uint32_t nowMs) {
    // Check connection status once every 1000ms to avoid high-frequency WiFi SDK calls
    static uint32_t lastCheckMs = 0;
    if (nowMs - lastCheckMs < 500 && lastCheckMs != 0) {
        return;
    }
    lastCheckMs = nowMs;    

    // 1. AP mode (pairing): green blinks evenly, no need to check mqtt/ntp/timeout
    if (WiFi.getMode() == WIFI_AP) {
        _ledConnRed.off();
        _ledConnGreen.blink(CONNECT_AP_BLINK_MS);
        return;
    }

    // 2. WiFi STA connection
    if (WiFi.status() != WL_CONNECTED) {
        _ledConnGreen.off();
        _ledConnRed.blink(2, CONNECT_BLINK_ON, CONNECT_BLINK_OFF);
        return;
    }

    // 3. MQTT connection
    bool mqttOk = _services.isConnected ? _services.isConnected() : false;
    if (!mqttOk) {
        _ledConnGreen.off();
        _ledConnRed.blink(3, CONNECT_BLINK_ON, CONNECT_BLINK_OFF);
        return;
    }

    // 4. NTP sync
    bool ntpOk = _services.log && _services.log->isTimeSynced();
    if (!ntpOk) {
        _ledConnGreen.off();
        _ledConnRed.blink(4, CONNECT_BLINK_ON, CONNECT_BLINK_OFF);
        return;
    }

    // 5. Target status timeout (> 70s without status from target) -> solid red
    if (_cfg->targetId[0] != '\0' && nowMs - _lastStatusRxMs >= STATUS_TIMEOUT_MS) {
        _ledConnGreen.off();
        _ledConnRed.on();
        return;
    }

    // Healthy state: solid green
    _ledConnRed.off();
    _ledConnGreen.on();
}

void RemoteSwitchDriver::updateStateLeds() {
    switch (_visualState) {
    case TargetVisualState::ON_OK:
        _ledStateRed.off();
        _ledStateGreen.on();
        break;
    case TargetVisualState::WAITING:
        _ledStateRed.off();
        _ledStateGreen.blink(500);
        break;
    case TargetVisualState::ERROR:
        _ledStateGreen.off();
        _ledStateRed.blink(500);
        break;
    case TargetVisualState::OFF:
    default:
        _ledStateGreen.off();
        _ledStateRed.off();
        break;
    }
}

void RemoteSwitchDriver::enterVisualState(TargetVisualState state, uint32_t nowMs) {
    if (_visualState == state) return;
    _visualState = state;
    switch (state) {
    case TargetVisualState::WAITING:
        _waitStartMs = nowMs;
        break;
    case TargetVisualState::ERROR:
        _errorStartMs = nowMs;
        break;
    default:
        break;
    }
}

// Cập nhật cặp 2 theo status vừa nhận từ target.
// Chặt: xanh sáng mãi chỉ khi relay on VÀ pumpStateStr == "RUNNING OK".
void RemoteSwitchDriver::updateVisualFromStatus(uint32_t nowMs) {
    switch (_visualState) {
    case TargetVisualState::WAITING:
        // Chỉ rời WAITING khi có kết luận rõ ràng
        if (!_targetOn) {
            enterVisualState(TargetVisualState::OFF, nowMs);
        }
        else if (_targetError) {
            enterVisualState(TargetVisualState::ERROR, nowMs);
        }
        else if (isTargetRunningOk()) {
            enterVisualState(TargetVisualState::ON_OK, nowMs);
        }
        // relay on nhưng chưa RUNNING OK → tiếp tục nháy xanh chờ
        break;

    case TargetVisualState::ON_OK:
        if (!_targetOn) {
            enterVisualState(TargetVisualState::OFF, nowMs);
        }
        else if (_targetError) {
            enterVisualState(TargetVisualState::ERROR, nowMs);
        }
        break;

    case TargetVisualState::ERROR:
        if (!_targetOn) {
            enterVisualState(TargetVisualState::OFF, nowMs);
        }
        // target vẫn lỗi → giữ ERROR; timer 60s tự tắt vẫn chạy
        break;

    case TargetVisualState::OFF:
    default:
        if (_targetOn && _targetError) {
            enterVisualState(TargetVisualState::ERROR, nowMs);
        }
        else if (_targetOn && isTargetRunningOk()) {
            enterVisualState(TargetVisualState::ON_OK, nowMs);
        }
        break;
    }
}

bool RemoteSwitchDriver::isTargetRunningOk() const {
    if (strcmp_P(_cfg->targetType, PSTR("switch")) == 0) {
        return _targetOn;
    }
    // pump: chỉ coi là OK khi relay on + state RUNNING OK
    return _targetOn && strcmp_P(_targetPumpStateStr, PSTR("RUNNING OK")) == 0;
}

// Subscribe tới topic trạng thái của target (core ghi nhớ + re-subscribe khi reconnect)
void RemoteSwitchDriver::subscribeTargetTopic() {
    if (!_services.mqttSubscribe) return;
    if (_cfg->targetId[0] == '\0') return;
    _services.mqttSubscribe(String(F("devices/")) + _cfg->targetId + F("/up"));
}

void RemoteSwitchDriver::sendRelayCommand(bool on) {
    if (_cfg->targetId[0] == '\0') return;

    uint32_t ts = _services.log ? _services.log->getEpoch() : 0;
    const char* src = _services.deviceId ? _services.deviceId : "";

    char hmacHex[65] = {0};
    if (_cfg->targetKey[0] != '\0') {
        const String canonical = crypto::buildCanonical(ts, "setRelay", "", src);
        crypto::hmacSha256HexKey(_cfg->targetKey, canonical.c_str(), canonical.length(), hmacHex);
    }

    protocol::BinaryWriter writer(protocol::CommandId::SetRelay);
    writer.writeBool(protocol::FieldId::State, on);
    writer.writeU32(protocol::FieldId::Ts, ts);
    if (src[0] != '\0') writer.writeString(protocol::FieldId::Src, src);
    if (hmacHex[0] != '\0') writer.writeString(protocol::FieldId::Hmac, hmacHex);

    String topic = String(F("devices/")) + _cfg->targetId + F("/cmd");
    LT_IM(CMD, "Sending binary setRelay(%s) to %s", on ? "ON" : "OFF", _cfg->targetId);
    if (_services.mqttPublishBinary) {
        _services.mqttPublishBinary(topic, writer.data(), writer.size());
    }
}

// Yêu cầu target gửi status stream 1 lần: bơm/switch chuyển sang tự báo mỗi 2s
// (thay vì 60s) → bên này nhận được status liên tục trong lúc chờ phản hồi.
void RemoteSwitchDriver::requestStatusStream() {
    if (_cfg->targetId[0] == '\0') return;

    uint32_t ts = _services.log ? _services.log->getEpoch() : 0;
    const char* src = _services.deviceId ? _services.deviceId : "";

    char hmacHex[65] = {0};
    if (_cfg->targetKey[0] != '\0') {
        const String canonical = crypto::buildCanonical(ts, "getStatus", "", src);
        crypto::hmacSha256HexKey(_cfg->targetKey, canonical.c_str(), canonical.length(), hmacHex);
    }

    protocol::BinaryWriter writer(protocol::CommandId::GetStatus);
    writer.writeBool(protocol::FieldId::Stream, true);
    writer.writeU32(protocol::FieldId::Ts, ts);
    if (src[0] != '\0') writer.writeString(protocol::FieldId::Src, src);
    if (hmacHex[0] != '\0') writer.writeString(protocol::FieldId::Hmac, hmacHex);

    String topic = String(F("devices/")) + _cfg->targetId + F("/cmd");
    LT_IM(CMD, "Requesting binary status stream from %s", _cfg->targetId);
    if (_services.mqttPublishBinary) {
        _services.mqttPublishBinary(topic, writer.data(), writer.size());
    }
}

void RemoteSwitchDriver::handleTargetStatus(uint8_t cmdId, const protocol::CommandRequest& doc) {
    // Mọi message trên devices/{targetId}/up đều chứng minh target còn sống
    _lastStatusRxMs = millis();

    // Reply của target đối với setRelay chuyển tiếp: {state: "on" | "off"}
    if (cmdId == static_cast<uint8_t>(protocol::CommandId::SetRelay)) {
        String st;
        if (doc.getString(protocol::FieldId::State, st)) {
            if (st == "on") {
                _targetOn = true;
                // Ack "on" chưa khẳng định RUNNING OK → giữ WAITING chờ status
            } else if (st == "off") {
                _targetOn = false;
                enterVisualState(TargetVisualState::OFF, millis());
            }
        }
        return;
    }

    // Status snapshot của target: relay state + pump protection state
    if (cmdId == static_cast<uint8_t>(protocol::CommandId::GetStatus)) {
        bool relay = false;
        if (doc.getBool(protocol::FieldId::Relay, relay)) {
            _targetOn = relay;
        }
        updateTargetError(doc);
        updateVisualFromStatus(millis());
        return;
    }

    // Unknown message (announce, ...): fall back to parsing any fields present
    bool relay = false;
    if (doc.getBool(protocol::FieldId::Relay, relay)) {
        _targetOn = relay;
    }
    String st;
    if (doc.getString(protocol::FieldId::State, st)) {
        if (st == "on") {
            _targetOn = true;
        } else if (st == "off") {
            _targetOn = false;
        }
    }
    updateTargetError(doc);
    updateVisualFromStatus(millis());
}

void RemoteSwitchDriver::updateTargetError(const protocol::CommandRequest& doc) {
    // Lưu state pump để xác định điều kiện "chặt" RUNNING OK
    String pumpStateStr;
    if (doc.getString(protocol::FieldId::PumpStateStr, pumpStateStr)) {
        strlcpy(_targetPumpStateStr, pumpStateStr.c_str(), sizeof(_targetPumpStateStr));
    } else {
        int32_t s = 0;
        if (doc.getInt(protocol::FieldId::PumpState, s)) {
            switch (s) {
            case 0: strlcpy(_targetPumpStateStr, "OFF", sizeof(_targetPumpStateStr)); break;
            case 1: strlcpy(_targetPumpStateStr, "RUNNING OK", sizeof(_targetPumpStateStr)); break;
            case 2: strlcpy(_targetPumpStateStr, "HIGH CURRENT", sizeof(_targetPumpStateStr)); break;
            case 3: strlcpy(_targetPumpStateStr, "DRY RUN", sizeof(_targetPumpStateStr)); break;
            case 4: strlcpy(_targetPumpStateStr, "CRITICAL CURRENT", sizeof(_targetPumpStateStr)); break;
            case 5: strlcpy(_targetPumpStateStr, "OVERLOAD", sizeof(_targetPumpStateStr)); break;
            default: _targetPumpStateStr[0] = '\0'; break;
            }
        } else {
            _targetPumpStateStr[0] = '\0';
        }
    }

    _targetError = false;

    // Pump errors — top level getStatus fields (pumpStateStr / pumpState enum int)
    if (strcmp_P(_targetPumpStateStr, PSTR("DRY RUN")) == 0 || strcmp_P(_targetPumpStateStr, PSTR("OVERLOAD")) == 0 || strcmp_P(_targetPumpStateStr, PSTR("CRITICAL CURRENT")) == 0) {
        _targetError = true;
    }
    int32_t s = 0;
    if (doc.getInt(protocol::FieldId::PumpState, s)) {
        if (s == 3 || s == 4 || s == 5) _targetError = true; // PumpState::DRY_RUN / CRITICAL_CURRENT / OVERLOAD
    }
}

bool RemoteSwitchDriver::handleCmd(const char* cmd, const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    if (strcmp_P(cmd, PSTR("setRelay")) == 0) {
        bool on = false;
        if (!payload.getBool(protocol::FieldId::State, on)) {
            resp.setString(protocol::FieldId::Status, F("error"));
            resp.setString(protocol::FieldId::Message, F("Missing or invalid 'state' field"));
            return true;
        }
        sendRelayCommand(on);
        if (_services.log) {
            _services.log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
        }
        resp.setString(protocol::FieldId::Status, F("ok"));
        resp.setString(protocol::FieldId::State, on ? F("on") : F("off"));
        LT_IM(CMD, "Forward relay %s to target %s", on ? "ON" : "OFF", _cfg->targetId);
        return true;
    }
    return false;
}

void RemoteSwitchDriver::getStatus(protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::TargetId, _cfg->targetId);
    resp.setString(protocol::FieldId::TargetType, _cfg->targetType);
    resp.setBool(protocol::FieldId::TargetPaired, _cfg->targetKey[0] != '\0');
    resp.setBool(protocol::FieldId::Relay, _targetOn);
    resp.setBool(protocol::FieldId::TargetError, _targetError);
}

void RemoteSwitchDriver::getConfig(protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::TargetId, _cfg->targetId);
    resp.setString(protocol::FieldId::TargetType, _cfg->targetType);
    resp.setString(protocol::FieldId::TargetKey, _cfg->targetKey[0] != '\0' ? F("********") : F(""));
}

bool RemoteSwitchDriver::setConfig(const protocol::CommandRequest& payload, protocol::CommandResponse& resp) {
    (void)resp;
    bool changed = false;

    String id;
    if (payload.getString(protocol::FieldId::TargetId, id)) {
        if (id.length() >= sizeof(_cfg->targetId)) {
            LT_EM(CMD, "setConfig: targetId too long");
            return false;
        }
        strlcpy(_cfg->targetId, id.c_str(), sizeof(_cfg->targetId));
        changed = true;
        subscribeTargetTopic();
    }
    String k;
    if (payload.getString(protocol::FieldId::TargetKey, k)) {
        size_t klen = k.length();
        if (klen == 0) {
            _cfg->targetKey[0] = '\0';
            changed = true;
        } else if (klen == 64) {
            strlcpy(_cfg->targetKey, k.c_str(), sizeof(_cfg->targetKey));
            changed = true;
        } else if (klen == 128) {
            // E2E Encrypted targetKey: 64 bytes blob [ IV(16) | ciphertext(32) | tag(16) ]
            uint8_t blob[64];
            if (!crypto::hexDecode(k.c_str(), blob, sizeof(blob))) {
                LT_EM(CMD, "setConfig: invalid hex in targetKeyEnc");
                return false;
            }

            if (!_services.identity) {
                LT_EM(CMD, "setConfig: identity service unavailable for E2E decrypt");
                return false;
            }

            String myKeyHex;
            uint8_t myKey[32];
            if (!_services.identity->controlKeyHex(myKeyHex) || !crypto::hexDecode(myKeyHex.c_str(), myKey, sizeof(myKey))) {
                LT_EM(CMD, "setConfig: cannot retrieve my controlKey for decrypt");
                return false;
            }

            uint8_t plainTargetKey[32];
            if (!crypto::decryptKey(myKey, blob, plainTargetKey)) {
                LT_EM(CMD, "setConfig: targetKey E2E decrypt failed (bad tag/key)");
                return false;
            }

            crypto::hexEncode(plainTargetKey, sizeof(plainTargetKey), _cfg->targetKey);
            changed = true;
            LT_IM(CMD, "setConfig: targetKey successfully decrypted via E2E AES-GCM");
        } else {
            LT_EM(CMD, "setConfig: targetKey must be 64 (plain) or 128 (encrypted) hex chars or empty");
            return false;
        }
    }
    String t;
    if (payload.getString(protocol::FieldId::TargetType, t)) {
        if (t.length() > 0 && t != "pump" && t != "switch") {
            LT_EM(CMD, "setConfig: targetType must be 'pump', 'switch', or empty");
            return false;
        }
        strlcpy(_cfg->targetType, t.c_str(), sizeof(_cfg->targetType));
        changed = true;
    }
    return changed;
}

// ── Button & Menu ──

void RemoteSwitchDriver::_onButtonClick() {
    LT_IM(BTN, "Button click");
    if (_cfg->targetId[0] == '\0') return;

    // Nút bị khóa khi đang chờ phản hồi (long-press menu vẫn hoạt động)
    if (_visualState == TargetVisualState::WAITING) return;

    sendToggleCommand();
    // Bơm/switch chuyển sang tự báo 2s → status về ngay, không phải chờ 60s
    requestStatusStream();

    bool on = !_targetOn;
    if (_services.log) {
        _services.log->logToggle(LogManager::ToggleSource::TOGGLE_BUTTON, on);
    }

    enterVisualState(TargetVisualState::WAITING, millis());

    // Notify app so its UI updates (same shape as setRelay response)
    protocol::BinaryWriter writer(protocol::CommandId::SetRelay);
    writer.writeString(protocol::FieldId::Status, "ok", 2);
    writer.writeString(protocol::FieldId::State, on ? "on" : "off", on ? 2 : 3);
    if (_services.sendBinaryResponse) {
        _services.sendBinaryResponse(writer.data(), writer.size());
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

void RemoteSwitchDriver::_staMqttMode() {
    LT_IM(BTN, "Button long press: Enter STA MQTT mode");
    _cfg->connMode = ConnMode::STA_MQTT;
    if (_saveCb) _saveCb();
    vTaskDelay(pdMS_TO_TICKS(1000));
    ESP.restart();
}
