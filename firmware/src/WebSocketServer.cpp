#include "WebSocketServer.h"

WebSocketServer::WebSocketServer(uint16_t port)
    : _server(nullptr)
    , _numClients(0)
    , _port(port)
    , _running(false)
{
    for (int i = 0; i < WEBSOCKETS_SERVER_CLIENT_MAX; i++) {
        _clientIds[i] = "";
    }
}

WebSocketServer::~WebSocketServer() {
    delete _server;
}

bool WebSocketServer::begin() {
    if (_server) {
        _server->begin();
        return false;
    }

    _server = new WebSocketsServer(_port);
    _server->setNoDelay(true);
    _server->onEvent([this](uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
        this->_onEvent(num, type, payload, length);
    });
    _server->begin();
    _running = true;
    return true;
}


void WebSocketServer::stop() {
    if (_server) {
        _server->close();
        delete _server;
        _server = nullptr;
    }
    _running = false;
    _numClients = 0;
    for (int i = 0; i < WEBSOCKETS_SERVER_CLIENT_MAX; i++) {
        _clientIds[i] = "";
    }
}

void WebSocketServer::handle() {
    if (!_running || !_server) return;
    _server->loop();
}

void WebSocketServer::broadcast(const String& message) {
    if (!_server) return;
    _server->broadcastTXT(message.c_str(), message.length());
}

bool WebSocketServer::send(const String& clientId, const String& message) {
    if (!_server) return false;
    int idx = _findClient(clientId);
    if (idx < 0) return false;
    return _server->sendTXT(idx, message.c_str(), message.length());
}

int WebSocketServer::clientCount() {
    return _numClients;
}

void WebSocketServer::setCallback(MessageCallback cb) {
    _callback = cb;
}

void WebSocketServer::setBinaryCallback(BinaryCallback cb) {
    _binaryCb = cb;
}

void WebSocketServer::_onEvent(uint8_t num, WStype_t type, uint8_t* payload, size_t length) {
    switch (type) {
        case WStype_CONNECTED:
            LT_IM(WS, "WebSocket client connected: %d", num);
            if (num < WEBSOCKETS_SERVER_CLIENT_MAX) {
                _clientIds[num] = "ws_" + String(num);
                _numClients++;
                
            }
            break;

        case WStype_DISCONNECTED:
            LT_IM(WS, "WebSocket client disconnected: %d", num);
            if (num < WEBSOCKETS_SERVER_CLIENT_MAX && _clientIds[num].length() > 0) {
                _clientIds[num] = "";
                _numClients--;
            }
            break;

        case WStype_TEXT:
            if (_callback && num < WEBSOCKETS_SERVER_CLIENT_MAX) {
                String msg((char*)payload, length);
                _callback(_clientIds[num], msg);
            }
            break;

        case WStype_BIN:
            if (_binaryCb && num < WEBSOCKETS_SERVER_CLIENT_MAX) {
                _binaryCb(_clientIds[num], payload, length);
            }
            break;

        default:
            break;
    }
}

int WebSocketServer::_findClient(const String& id) {
    for (int i = 0; i < WEBSOCKETS_SERVER_CLIENT_MAX; i++) {
        if (_clientIds[i] == id) return i;
    }
    return -1;
}
