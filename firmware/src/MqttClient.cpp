#include "MqttClient.h"
#include <Config.h>

static MqttClient* s_instance = nullptr;

MqttClient::MqttClient()
    : _mqtt(_wifiClient)
    , _port(DEFAULT_MQTT_PORT)
    , _lastReconnect(0)
{
    _mqtt.setBufferSize(MQTT_BUFFER_SIZE);
    _mqtt.setSocketTimeout(MQTT_SOCKET_TIMEOUT_SEC);
    _mqtt.setCallback(_onMessage);
    s_instance = this;
}

bool MqttClient::begin(const char* server, uint16_t port,
                       const char* user, const char* pass,
                       const char* clientId, const char* topic) {
    _server = server;
    _port = port;
    _user = user ? user : "";
    _pass = pass ? pass : "";
    _clientId = clientId ? clientId : DEVICE_NAME;
    _topic = topic ? topic : "pump";
    _useTls = (port != DEFAULT_MQTT_PORT);
    _mqtt.setServer(server, port);
    return true;
}

void MqttClient::setCallback(MessageCallback cb) {
    _callback = cb;
}

bool MqttClient::connect() {
    if (_mqtt.connected()) return true;

    _lastReconnect = millis();

    if (!_useTls) {
        _mqtt.setClient(_wifiClient);
        bool ok = _mqtt.connect(_clientId.c_str(), _user.c_str(), _pass.c_str());
        if (ok) {
            _mqtt.subscribe((_topic + "/cmd").c_str());
            _mqtt.subscribe((_topic + "/otachunk").c_str());
        }
        return ok;
    }

    // Free SSL context, then close TCP and clear _connected
    _wifiClientTls.stop();
    _wifiClientTls.LwIPClient::stop();
    delay(10);
    _wifiClientTls.setInsecure();
    _mqtt.setClient(_wifiClientTls);

    bool ok = _mqtt.connect(_clientId.c_str(), _user.c_str(), _pass.c_str());
    if (ok) {
        _mqtt.subscribe((_topic + "/cmd").c_str());
        _mqtt.subscribe((_topic + "/otachunk").c_str());
    }
    return ok;
}

void MqttClient::disconnect() {
    _mqtt.disconnect();
}

bool MqttClient::publish(const String& topic, const String& payload, bool retained) {
    if (_useTls && !_wifiClientTls.connected()) {
        return false;
    }
    return _mqtt.publish(topic.c_str(), payload.c_str(), retained);
}

bool MqttClient::subscribe(const String& topic) {
    return _mqtt.subscribe(topic.c_str());
}

bool MqttClient::loop() {
    if (!_mqtt.connected()) {
        if (millis() - _lastReconnect > MQTT_RECONNECT_INTERVAL_MS) return connect();
        return false;
    }

    if (_useTls) {
        int avail = _wifiClientTls.available();
        if (avail < 0) {
            _wifiClientTls.LwIPClient::stop();
            return false;
        }
    }

    return _mqtt.loop();
}

bool MqttClient::isConnected() {
    return _mqtt.connected();
}

void MqttClient::_onMessage(char* topic, uint8_t* payload, unsigned int len) {
    if (!s_instance) return;
    String msg(reinterpret_cast<char*>(payload), len);
    if (s_instance->_callback) {
        s_instance->_callback(String(topic), msg);
    }
}
