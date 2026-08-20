#pragma once

#include <Arduino.h>
#include <PubSubClient.h>
#include "compat/wifi.h"
#include <WiFiClientSecure.h>
#include <functional>
#include <vector>

class MqttClient {
public:
    using MessageCallback = std::function<void(const String& topic, const String& payload)>;

    MqttClient();
    bool begin(const char* server, uint16_t port,
               const char* user, const char* pass,
               const char* clientId, const char* topic);
    void setCallback(MessageCallback cb);
    bool connect();
    void disconnect();
    bool publish(const String& topic, const String& payload, bool retained = false);
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
    uint16_t _port;
    String _user;
    String _pass;
    String _clientId;
    String _topic;
    MessageCallback _callback;
    unsigned long _lastReconnect = 0;

    static void _onMessage(char* topic, uint8_t* payload, unsigned int len);
};
