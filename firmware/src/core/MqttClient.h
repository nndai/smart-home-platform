#pragma once

#include <Arduino.h>
#include <PubSubClient.h>
#include "compat/wifi.h"
#include <WiFiClientSecure.h>
#include <functional>
#include <vector>

class MqttClient {
public:
    using BinaryCallback = std::function<void(const String& topic, const uint8_t* payload, size_t length)>;

    MqttClient();
    bool begin(const char* server, uint16_t port,
               const char* user, const char* pass,
               const char* clientId, const char* topic);
    void setCallback(BinaryCallback cb);
    bool connect();
    void disconnect();
    bool publishBinary(const String& topic, const uint8_t* payload, size_t length, bool retained = false);
    bool subscribe(const String& topic);
    bool loop();
    bool isConnected();
    const String& getTopic() const { return _topic; }
    
    void subscribeExtra(const String& topic);
    void resubscribeExtra();
    bool isExtraTopic(const String& topic) const;

private:
    std::vector<String> _extraTopics;
    WiFiClient _wifiClient;
    WiFiClientSecure _wifiClientTls;
    bool _useTls = false;
    PubSubClient _mqtt;
    String _server;
    uint16_t _port = 0;
    String _user;
    String _pass;
    String _clientId;
    String _topic;
    BinaryCallback _callback;
    unsigned long _lastReconnect = 0;

    static void _onMessage(char* topic, uint8_t* payload, unsigned int len);
};
