#include "core/MqttClient.h"
#include <Config.h>
#include "compat/tls.h"
#include <algorithm>

static MqttClient* s_instance = nullptr;

MqttClient::MqttClient()
    : _mqtt(_wifiClient)
    , _port(DEFAULT_MQTT_PORT)
    , _lastReconnect(0)
{
    _mqtt.setBufferSize(MQTT_BUFFER_SIZE);
    compat::setTlsBufferSize(_wifiClientTls, MQTT_BUFFER_SIZE, MQTT_BUFFER_SIZE);
    _mqtt.setSocketTimeout(MQTT_SOCKET_TIMEOUT_SEC);
    _mqtt.setCallback(_onMessage);
    s_instance = this;
}

bool MqttClient::begin(const char* server, uint16_t port,
                       const char* user, const char* pass,
                       const char* clientId, const char* topic) {
    _server = server ? server : "";
    _port = port;
    _user = user ? user : "";
    _pass = pass ? pass : "";
    _clientId = clientId ? clientId : "unknown_client";
    _topic = topic ? topic : "pump";
    _useTls = (port != DEFAULT_MQTT_PORT);
    _mqtt.setServer(server, port);
    return true;
}

void MqttClient::setCallback(BinaryCallback cb) {
    _callback = cb;
}

bool MqttClient::connect() {
    if (isConnected()) return true;

    _lastReconnect = millis();

    if (!_useTls) {
        _mqtt.setClient(_wifiClient);
        bool ok = _mqtt.connect(_clientId.c_str(), _user.c_str(), _pass.c_str());
        if (ok) {
            _mqtt.subscribe((_topic + F("/cmd")).c_str());
            resubscribeExtra();
        }
        return ok;
    }

    // Free SSL context, then close TCP and clear _connected
    compat::tlsReset(_wifiClientTls);
    _wifiClientTls.setInsecure();
    _mqtt.setClient(_wifiClientTls);

    bool ok = _mqtt.connect(_clientId.c_str(), _user.c_str(), _pass.c_str());
    if (ok) {
        _mqtt.subscribe((_topic + F("/cmd")).c_str());
        resubscribeExtra();
    }
    return ok;
}

void MqttClient::disconnect() {
    _mqtt.disconnect();
}

bool MqttClient::publishBinary(const String& topic, const uint8_t* payload, size_t length, bool retained) {
    if (!isConnected()) {
        return false;
    }
    return _mqtt.publish(topic.c_str(), payload, length, retained);
}

bool MqttClient::subscribe(const String& topic) {
    if (!isConnected()) {
        return false;
    }
    return _mqtt.subscribe(topic.c_str());
}

bool MqttClient::loop() {
    if (!isConnected()) {
        if (millis() - _lastReconnect > MQTT_RECONNECT_INTERVAL_MS) return connect();
        return false;
    }

    if (_useTls && !compat::tlsCheckHealth(_wifiClientTls)) {
        return false;
    }

    return _mqtt.loop();
}

bool MqttClient::isConnected() {
    return _mqtt.state() == MQTT_CONNECTED;
}

void MqttClient::_onMessage(char* topic, uint8_t* payload, unsigned int len) {
    if (!s_instance || !topic || !payload || len == 0) return;
    if (s_instance->_callback) {
        s_instance->_callback(String(topic), payload, len);
    }
}

void MqttClient::subscribeExtra(const String& topic) {
    if (topic.length() == 0) return;
    if (std::find(_extraTopics.begin(), _extraTopics.end(), topic) != _extraTopics.end()) return;
    
    if (_extraTopics.size() < 16) {
        _extraTopics.push_back(topic);
    }
    if (isConnected()) {
        _mqtt.subscribe(topic.c_str());
    }
}

void MqttClient::resubscribeExtra() {
    for (const auto& t : _extraTopics) {
        _mqtt.subscribe(t.c_str());
    }
}

bool MqttClient::isExtraTopic(const String& topic) const {
    return std::find(_extraTopics.begin(), _extraTopics.end(), topic) != _extraTopics.end();
}
