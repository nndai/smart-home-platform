#include "core/MqttClient.h"
#include <Config.h>
#include "compat/tls.h"
#include <algorithm>

static MqttClient* s_instance = nullptr;

MqttClient::MqttClient()
    : _wifiClient(nullptr)
    , _wifiClientTls(nullptr)
    , _useTls(false)
    , _mqtt(nullptr)
    , _port(DEFAULT_MQTT_PORT)
    , _lastReconnect(0)
{
    s_instance = this;
}

MqttClient::~MqttClient() {
    _cleanup();
}

void MqttClient::_cleanup() {
    if (_mqtt) {
        delete _mqtt;
        _mqtt = nullptr;
    }
    if (_wifiClientTls) {
        delete _wifiClientTls;
        _wifiClientTls = nullptr;
    }
    if (_wifiClient) {
        delete _wifiClient;
        _wifiClient = nullptr;
    }
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

    // Free previous instances if re-initializing
    _cleanup();

    // Allocate network client based on TLS setting
    if (_useTls) {
        _wifiClientTls = new WiFiClientSecure();
        compat::setTlsBufferSize(*_wifiClientTls, MQTT_BUFFER_SIZE);
        _wifiClientTls->setInsecure();
        _mqtt = new PubSubClient(*_wifiClientTls);
    } else {
        _wifiClient = new WiFiClient();
        _mqtt = new PubSubClient(*_wifiClient);
    }

    _mqtt->setServer(server, port);
    _mqtt->setBufferSize(MQTT_BUFFER_SIZE);
    _mqtt->setSocketTimeout(MQTT_SOCKET_TIMEOUT_SEC);
    _mqtt->setCallback(_onMessage);

    return true;
}

void MqttClient::setCallback(MessageCallback cb) {
    _callback = cb;
}

bool MqttClient::connect() {
    if (isConnected()) return true;
    if (!_mqtt) return false;

    _lastReconnect = millis();

    if (!_useTls) {
        if (_wifiClient) {
            _mqtt->setClient(*_wifiClient);
        }
        bool ok = _mqtt->connect(_clientId.c_str(), _user.c_str(), _pass.c_str());
        if (ok) {
            _mqtt->subscribe((_topic + "/cmd").c_str());
            _mqtt->subscribe((_topic + "/otachunk").c_str());
            resubscribeExtra();
        }
        return ok;
    }

    if (_wifiClientTls) {
        // Free SSL context, then close TCP and clear _connected
        compat::tlsReset(*_wifiClientTls);
        _wifiClientTls->setInsecure();
        _mqtt->setClient(*_wifiClientTls);
    }

    bool ok = _mqtt->connect(_clientId.c_str(), _user.c_str(), _pass.c_str());
    if (ok) {
        _mqtt->subscribe((_topic + "/cmd").c_str());
        _mqtt->subscribe((_topic + "/otachunk").c_str());
        resubscribeExtra();
    }
    return ok;
}

void MqttClient::disconnect() {
    if (_mqtt) {
        _mqtt->disconnect();
    }
}

bool MqttClient::publish(const String& topic, const String& payload, bool retained) {
    if (!isConnected() || !_mqtt) {
        return false;
    }
    return _mqtt->publish(topic.c_str(), payload.c_str(), retained);
}

bool MqttClient::subscribe(const String& topic) {
    if (!isConnected() || !_mqtt) {
        return false;
    }
    return _mqtt->subscribe(topic.c_str());
}

bool MqttClient::loop() {
    if (!_mqtt) return false;

    if (!isConnected()) {
        if (millis() - _lastReconnect > MQTT_RECONNECT_INTERVAL_MS) return connect();
        return false;
    }

    if (_useTls && !compat::tlsCheckHealth(_wifiClientTls)) {
        return false;
    }

    return _mqtt->loop();
}

bool MqttClient::isConnected() {
    return _mqtt && (_mqtt->state() == MQTT_CONNECTED);
}

void MqttClient::_onMessage(char* topic, uint8_t* payload, unsigned int len) {
    if (!s_instance || !topic) return;
    String msg;
    if (payload && len > 0) {
        msg.concat((const char*)payload, len);
    }
    if (s_instance->_callback) {
        s_instance->_callback(String(topic), msg);
    }
}

void MqttClient::subscribeExtra(const String& topic) {
    if (topic.length() == 0) return;
    if (std::find(_extraTopics.begin(), _extraTopics.end(), topic) != _extraTopics.end()) return;
    
    if (_extraTopics.size() < 16) {
        _extraTopics.push_back(topic);
    }
    if (isConnected() && _mqtt) {
        _mqtt->subscribe(topic.c_str());
    }
}

void MqttClient::resubscribeExtra() {
    if (!_mqtt) return;
    for (const auto& t : _extraTopics) {
        _mqtt->subscribe(t.c_str());
    }
}

bool MqttClient::isExtraTopic(const String& topic) const {
    return std::find(_extraTopics.begin(), _extraTopics.end(), topic) != _extraTopics.end();
}
