#pragma once

#include <Arduino.h>
#include <WebSocketsServer.h>
#include <functional>

class WebSocketServer {
public:
    using MessageCallback = std::function<void(const String& clientId, const String& message)>;
    using BinaryCallback = std::function<void(const String& clientId, const uint8_t* data, size_t len)>;

    WebSocketServer(uint16_t port);
    ~WebSocketServer();
    bool begin();
    void stop();
    void handle();
    void broadcast(const String& message);
    bool send(const String& clientId, const String& message);
    int clientCount();
    void setCallback(MessageCallback cb);
    void setBinaryCallback(BinaryCallback cb);

private:
    WebSocketsServer* _server;
    String _clientIds[WEBSOCKETS_SERVER_CLIENT_MAX];
    int _numClients;
    uint16_t _port;
    bool _running;
    MessageCallback _callback;
    BinaryCallback _binaryCb;

    void _onEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length);
    int _findClient(const String& id);
};
