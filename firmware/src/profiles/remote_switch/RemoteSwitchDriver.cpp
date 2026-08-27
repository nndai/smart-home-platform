#include "RemoteSwitchDriver.h"
#include <Arduino.h>
#include "compat/wifi.h"
#include "core/Crypto.h"
#include "core/DeviceIdentity.h"


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

bool RemoteSwitchDriver::buildEnvelope(const char* cmd, const JsonDocument& payload, JsonDocument& envelope) {
    if (_cfg->targetKey[0] == '\0') return false;

    // Use current time as sequence if available, else monotonic counter
    uint32_t seq = _targetSeq++;
    if (_services.log && _services.log->isTimeSynced()) {
        seq = _services.log->getEpoch(); // epoch survives reboots (no persistence)
    }
    uint32_t ts = _services.log ? _services.log->getEpoch() : 0;

    String payloadStr;
    serializeJson(payload, payloadStr);

    // src = our deviceId: target tracks seq per sender, so the app and this
    // remote switch never lock each other out (docs §3.2)
    const char* src = _services.deviceId ? _services.deviceId : "";
    const String canonical = crypto::buildCanonical(seq, ts, cmd, payloadStr, src);

    char hmacHex[65];
    if (!crypto::hmacSha256HexKey(_cfg->targetKey, canonical.c_str(), canonical.length(), hmacHex)) {
        return false;
    }

    envelope[F("cmd")] = cmd;
    envelope[F("seq")] = seq;
    envelope[F("ts")] = ts;
    if (src[0] != '\0') envelope[F("src")] = src;
    envelope[F("hmac")] = hmacHex;
    envelope[F("payload")] = payload;

    return true;
}

// Subscribe tới topic trạng thái của target (core ghi nhớ + re-subscribe khi reconnect)
void RemoteSwitchDriver::subscribeTargetTopic() {
    if (!_services.mqttSubscribe) return;
    if (_cfg->targetId[0] == '\0') return;
    _services.mqttSubscribe(String(F("devices/")) + _cfg->targetId + F("/up"));
}

void RemoteSwitchDriver::sendRelayCommand(bool on) {
    if (_cfg->targetId[0] == '\0') return;

    JsonDocument payload;
    payload[F("state")] = on;

    JsonDocument envelope;
    if (!buildEnvelope("setRelay", payload, envelope)) {
        LT_EM(CMD, "Cannot build target envelope (targetKey missing?)");
        return;
    }

    String json;
    serializeJson(envelope, json);
    String topic = String(F("devices/")) + _cfg->targetId + F("/cmd");
    LT_IM(CMD, "Sending setRelay(%s) to %s", on ? "ON" : "OFF", _cfg->targetId);
    if (_services.mqttPublish) {
        _services.mqttPublish(topic, json);
    }
}

// Yêu cầu target gửi status stream 1 lần: bơm/switch chuyển sang tự báo mỗi 2s
// (thay vì 60s) → bên này nhận được status liên tục trong lúc chờ phản hồi.
void RemoteSwitchDriver::requestStatusStream() {
    if (_cfg->targetId[0] == '\0') return;

    JsonDocument payload;
    payload[F("stream")] = true;

    JsonDocument envelope;
    if (!buildEnvelope("getStatus", payload, envelope)) {
        LT_EM(CMD, "Cannot build target envelope (targetKey missing?)");
        return;
    }

    String json;
    serializeJson(envelope, json);
    String topic = String(F("devices/")) + _cfg->targetId + F("/cmd");
    LT_IM(CMD, "Requesting status stream from %s", _cfg->targetId);
    if (_services.mqttPublish) {
        _services.mqttPublish(topic, json);
    }
}

void RemoteSwitchDriver::handleTargetStatus(const JsonDocument& doc) {
    // Mọi message trên devices/{targetId}/up đều chứng minh target còn sống
    _lastStatusRxMs = millis();

    // Reply của target đối với setRelay chuyển tiếp: {state: "on" | "off"}
    const char* cmd = doc[F("cmd")] | "";
    if (strcmp_P(cmd, PSTR("setRelay")) == 0) {
        const char* st = doc[F("state")].as<const char*>();
        if (st && strcmp_P(st, PSTR("on")) == 0) {
            _targetOn = true;
            // Ack "on" chưa khẳng định RUNNING OK → giữ WAITING chờ status
        } else if (st && strcmp_P(st, PSTR("off")) == 0) {
            _targetOn = false;
            enterVisualState(TargetVisualState::OFF, millis());
        }
        return;
    }

    // Status snapshot của target: relay state + pump protection state
    if (strcmp_P(cmd, PSTR("getStatus")) == 0) {
        if (doc[F("relay")].is<bool>()) {
            _targetOn = doc[F("relay")].as<bool>();
        }
        updateTargetError(doc);
        updateVisualFromStatus(millis());
        return;
    }

    // Unknown message (announce, ...): fall back to parsing any fields present
    if (doc[F("relay")].is<bool>()) {
        _targetOn = doc[F("relay")].as<bool>();
    }
    const char* st = doc[F("state")].as<const char*>();
    if (st && strcmp_P(st, PSTR("on")) == 0) {
        _targetOn = true;
    } else if (st && strcmp_P(st, PSTR("off")) == 0) {
        _targetOn = false;
    }
    updateTargetError(doc);
    updateVisualFromStatus(millis());
}

void RemoteSwitchDriver::updateTargetError(const JsonDocument& doc) {
    // Lưu state pump để xác định điều kiện "chặt" RUNNING OK
    const char* pumpStateStr = doc[F("pumpStateStr")] | "";
    strlcpy(_targetPumpStateStr, pumpStateStr, sizeof(_targetPumpStateStr));

    _targetError = false;

    // Pump errors — top level getStatus fields (pumpStateStr / pumpState enum int)
    if (strcmp_P(_targetPumpStateStr, PSTR("DRY RUN")) == 0 || strcmp_P(_targetPumpStateStr, PSTR("OVERLOAD")) == 0 || strcmp_P(_targetPumpStateStr, PSTR("CRITICAL CURRENT")) == 0) {
        _targetError = true;
    }
    if (doc[F("pumpState")].is<int>()) {
        int s = doc[F("pumpState")].as<int>();
        if (s == 3 || s == 4 || s == 5) _targetError = true; // PumpState::DRY_RUN / CRITICAL_CURRENT / OVERLOAD
    }

    // Pump errors — getSystemInfo stream (nested pump.pumpState)
    if (doc[F("pump")].is<JsonObject>()) {
        JsonObjectConst p = doc[F("pump")].as<JsonObjectConst>();
        const char* ps = p[F("pumpState")] | "";
        if (strcmp_P(ps, PSTR("dry_run")) == 0 || strcmp_P(ps, PSTR("overload")) == 0 || strcmp_P(ps, PSTR("critical_current")) == 0) {
            _targetError = true;
        }
    }
}

bool RemoteSwitchDriver::handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) {
    if (strcmp_P(cmd, PSTR("setRelay")) == 0) {
        if (!payload[F("state")].is<bool>()) {
            resp[F("status")] = F("error");
            resp[F("message")] = F("Missing or invalid 'state' field");
            return true;
        }
        bool on = payload[F("state")].as<bool>();
        sendRelayCommand(on);
        if (_services.log) {
            _services.log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
        }
        resp[F("status")] = F("ok");
        resp[F("state")] = on ? F("on") : F("off");
        LT_IM(CMD, "Forward relay %s to target %s", on ? "ON" : "OFF", _cfg->targetId);
        return true;
    }
    return false;
}

void RemoteSwitchDriver::getStatus(JsonDocument& resp) {
    resp[F("targetId")] = _cfg->targetId;
    resp[F("targetType")] = _cfg->targetType;
    resp[F("targetPaired")] = _cfg->targetKey[0] != '\0';
    resp[F("relay")] = _targetOn;
    resp[F("targetError")] = _targetError;
}

void RemoteSwitchDriver::getConfig(JsonDocument& resp) {
    resp[F("targetId")] = _cfg->targetId;
    resp[F("targetType")] = _cfg->targetType;
    // targetKey is never exposed (same policy as mqttPass): only show whether it is set
    resp[F("targetKey")] = _cfg->targetKey[0] != '\0' ? F("********") : F("");
}

bool RemoteSwitchDriver::setConfig(const JsonDocument& payload, JsonDocument& resp) {
    (void)resp;
    bool changed = false;

    if (payload[F("targetId")].is<const char*>()) {
        const char* id = payload[F("targetId")].as<const char*>();
        if (strlen(id) >= sizeof(_cfg->targetId)) {
            LT_EM(CMD, "setConfig: targetId too long");
            return false;
        }
        strlcpy(_cfg->targetId, id, sizeof(_cfg->targetId));
        changed = true;
        subscribeTargetTopic();
    }
    if (payload[F("targetKey")].is<const char*>()) {
        const char* k = payload[F("targetKey")].as<const char*>();
        size_t klen = strlen(k);
        if (klen == 0) {
            _cfg->targetKey[0] = '\0';
            changed = true;
        } else if (klen == 64) {
            strlcpy(_cfg->targetKey, k, sizeof(_cfg->targetKey));
            changed = true;
        } else if (klen == 128) {
            // E2E Encrypted targetKey: 64 bytes blob [ IV(16) | ciphertext(32) | tag(16) ]
            uint8_t blob[64];
            if (!crypto::hexDecode(k, blob, sizeof(blob))) {
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
    if (payload[F("targetType")].is<const char*>()) {
        const char* t = payload[F("targetType")].as<const char*>();
        if (strlen(t) > 0 && strcmp_P(t, PSTR("pump")) != 0 && strcmp_P(t, PSTR("switch")) != 0) {
            LT_EM(CMD, "setConfig: targetType must be 'pump', 'switch', or empty");
            return false;
        }
        strlcpy(_cfg->targetType, t, sizeof(_cfg->targetType));
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
    JsonDocument resp;
    resp[F("cmd")] = F("setRelay");
    resp[F("status")] = F("ok");
    resp[F("state")] = on ? F("on") : F("off");
    String json;
    serializeJson(resp, json);
    if (_services.sendResponse) {
        _services.sendResponse(json);
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
