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
    (void)nowMs;

    // AP mode (pairing): chỉ cần AP đang chạy — xanh nháy đều,
    // không check mqtt/ntp/timeout status
    if (WiFi.getMode() == WIFI_AP) {
        _ledConnRed.off();
        _ledConnGreen.blink(CONNECT_AP_BLINK_MS);
        return;
    }

    // Check theo thứ tự: wifi → mqtt → ntp → timeout status (lỗi đầu tiên hiển thị)
    bool wifiOk = (WiFi.status() == WL_CONNECTED);
    if (!wifiOk) {
        _ledConnGreen.off();
        _ledConnRed.blink(2, CONNECT_BLINK_ON, CONNECT_BLINK_OFF);
        return;
    }

    bool mqttOk = _services.isConnected ? _services.isConnected() : false;
    if (!mqttOk) {
        _ledConnGreen.off();
        _ledConnRed.blink(3, CONNECT_BLINK_ON, CONNECT_BLINK_OFF);
        return;
    }

    bool ntpOk = _services.log && _services.log->isTimeSynced();
    if (!ntpOk) {
        _ledConnGreen.off();
        _ledConnRed.blink(4, CONNECT_BLINK_ON, CONNECT_BLINK_OFF);
        return;
    }

    // Quá 1p10s không nhận được status từ target → đỏ sáng mãi
    // (target tự báo status mỗi 60s khi rảnh / 2s khi stream → 70s có 10s dư)
    if (_cfg->targetId[0] != '\0' && nowMs - _lastStatusRxMs >= STATUS_TIMEOUT_MS) {
        _ledConnGreen.off();
        _ledConnRed.on();
        return;
    }

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
    if (strcmp(_cfg->targetType, "switch") == 0) {
        return _targetOn;
    }
    // pump: chỉ coi là OK khi relay on + state RUNNING OK
    return _targetOn && strcmp(_targetPumpStateStr, "RUNNING OK") == 0;
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

    envelope["cmd"] = cmd;
    envelope["seq"] = seq;
    envelope["ts"] = ts;
    if (src[0] != '\0') envelope["src"] = src;
    envelope["hmac"] = hmacHex;
    envelope["payload"] = payload;

    return true;
}

// Subscribe tới topic trạng thái của target (core ghi nhớ + re-subscribe khi reconnect)
void RemoteSwitchDriver::subscribeTargetTopic() {
    if (!_services.mqttSubscribe) return;
    if (_cfg->targetId[0] == '\0') return;
    _services.mqttSubscribe(String("devices/") + _cfg->targetId + "/up");
}

void RemoteSwitchDriver::sendRelayCommand(bool on) {
    if (_cfg->targetId[0] == '\0') return;

    JsonDocument payload;
    payload["state"] = on;

    JsonDocument envelope;
    if (!buildEnvelope("setRelay", payload, envelope)) {
        LT_EM(CMD, "Cannot build target envelope (targetKey missing?)");
        return;
    }

    String json;
    serializeJson(envelope, json);
    String topic = String("devices/") + _cfg->targetId + "/cmd";
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
    payload["stream"] = true;

    JsonDocument envelope;
    if (!buildEnvelope("getStatus", payload, envelope)) {
        LT_EM(CMD, "Cannot build target envelope (targetKey missing?)");
        return;
    }

    String json;
    serializeJson(envelope, json);
    String topic = String("devices/") + _cfg->targetId + "/cmd";
    LT_IM(CMD, "Requesting status stream from %s", _cfg->targetId);
    if (_services.mqttPublish) {
        _services.mqttPublish(topic, json);
    }
}

void RemoteSwitchDriver::handleTargetStatus(const JsonDocument& doc) {
    // Mọi message trên devices/{targetId}/up đều chứng minh target còn sống
    _lastStatusRxMs = millis();

    // Reply của target đối với setRelay chuyển tiếp: {state: "on" | "off"}
    const char* cmd = doc["cmd"] | "";
    if (strcmp(cmd, "setRelay") == 0) {
        const char* st = doc["state"].as<const char*>();
        if (st && strcmp(st, "on") == 0) {
            _targetOn = true;
            // Ack "on" chưa khẳng định RUNNING OK → giữ WAITING chờ status
        } else if (st && strcmp(st, "off") == 0) {
            _targetOn = false;
            enterVisualState(TargetVisualState::OFF, millis());
        }
        return;
    }

    // Status snapshot của target: relay state + pump protection state
    if (strcmp(cmd, "getStatus") == 0) {
        if (doc["relay"].is<bool>()) {
            _targetOn = doc["relay"].as<bool>();
        }
        updateTargetError(doc);
        updateVisualFromStatus(millis());
        return;
    }

    // Unknown message (announce, ...): fall back to parsing any fields present
    if (doc["relay"].is<bool>()) {
        _targetOn = doc["relay"].as<bool>();
    }
    const char* st = doc["state"].as<const char*>();
    if (st && strcmp(st, "on") == 0) {
        _targetOn = true;
    } else if (st && strcmp(st, "off") == 0) {
        _targetOn = false;
    }
    updateTargetError(doc);
    updateVisualFromStatus(millis());
}

void RemoteSwitchDriver::updateTargetError(const JsonDocument& doc) {
    // Lưu state pump để xác định điều kiện "chặt" RUNNING OK
    const char* pumpStateStr = doc["pumpStateStr"] | "";
    strlcpy(_targetPumpStateStr, pumpStateStr, sizeof(_targetPumpStateStr));

    _targetError = false;

    // Pump errors — top level getStatus fields (pumpStateStr / pumpState enum int)
    if (strcmp(_targetPumpStateStr, "DRY RUN") == 0 || strcmp(_targetPumpStateStr, "OVERLOAD") == 0 || strcmp(_targetPumpStateStr, "CRITICAL CURRENT") == 0) {
        _targetError = true;
    }
    if (doc["pumpState"].is<int>()) {
        int s = doc["pumpState"].as<int>();
        if (s == 3 || s == 4 || s == 5) _targetError = true; // PumpState::DRY_RUN / CRITICAL_CURRENT / OVERLOAD
    }

    // Pump errors — getSystemInfo stream (nested pump.pumpState)
    if (doc["pump"].is<JsonObject>()) {
        JsonObjectConst p = doc["pump"].as<JsonObjectConst>();
        const char* ps = p["pumpState"] | "";
        if (strcmp(ps, "dry_run") == 0 || strcmp(ps, "overload") == 0 || strcmp(ps, "critical_current") == 0) {
            _targetError = true;
        }
    }
}

bool RemoteSwitchDriver::handleCmd(const char* cmd, const JsonDocument& payload, JsonDocument& resp) {
    if (strcmp(cmd, "setRelay") == 0) {
        if (!payload["state"].is<bool>()) {
            resp["status"] = "error";
            resp["message"] = "Missing or invalid 'state' field";
            return true;
        }
        bool on = payload["state"].as<bool>();
        sendRelayCommand(on);
        if (_services.log) {
            _services.log->logToggle(LogManager::ToggleSource::TOGGLE_ONLINE, on);
        }
        resp["status"] = "ok";
        resp["state"] = on ? "on" : "off";
        LT_IM(CMD, "Forward relay %s to target %s", on ? "ON" : "OFF", _cfg->targetId);
        return true;
    }
    return false;
}

void RemoteSwitchDriver::getStatus(JsonDocument& resp) {
    resp["targetId"] = _cfg->targetId;
    resp["targetType"] = _cfg->targetType;
    resp["targetPaired"] = _cfg->targetKey[0] != '\0';
    resp["relay"] = _targetOn;
    resp["targetError"] = _targetError;
}

void RemoteSwitchDriver::getConfig(JsonDocument& resp) {
    resp["targetId"] = _cfg->targetId;
    resp["targetType"] = _cfg->targetType;
    // targetKey is never exposed (same policy as mqttPass): only show whether it is set
    resp["targetKey"] = _cfg->targetKey[0] != '\0' ? "********" : "";
}

bool RemoteSwitchDriver::setConfig(const JsonDocument& payload, JsonDocument& resp) {
    (void)resp;
    bool changed = false;

    if (payload["targetId"].is<const char*>()) {
        const char* id = payload["targetId"].as<const char*>();
        if (strlen(id) >= sizeof(_cfg->targetId)) {
            LT_EM(CMD, "setConfig: targetId too long");
            return false;
        }
        strlcpy(_cfg->targetId, id, sizeof(_cfg->targetId));
        changed = true;
        subscribeTargetTopic();
    }
    if (payload["targetKey"].is<const char*>()) {
        const char* k = payload["targetKey"].as<const char*>();
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
    if (payload["targetType"].is<const char*>()) {
        const char* t = payload["targetType"].as<const char*>();
        if (strlen(t) > 0 && strcmp(t, "pump") != 0 && strcmp(t, "switch") != 0) {
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
    resp["cmd"] = "setRelay";
    resp["status"] = "ok";
    resp["state"] = on ? "on" : "off";
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
