#pragma once

// ── HTTPClient header đa nền tảng ──
#if defined(ARDUINO_ARCH_ESP8266)
#include <ESP8266HTTPClient.h>
#include <WiFiClient.h>

class Esp8266HTTPClientWrapper {
private:
    HTTPClient _client;
    WiFiClient _wifiClient;
public:
    bool begin(const String& url) {
        return _client.begin(_wifiClient, url);
    }
    bool begin(WiFiClient& client, const String& url) {
        return _client.begin(client, url);
    }
    void setFollowRedirects(followRedirects_t follow) {
        _client.setFollowRedirects(follow);
    }
    void addHeader(const String& header, const String& value) {
        _client.addHeader(header, value);
    }
    int GET() { return _client.GET(); }
    void end() { _client.end(); }
    int getSize() { return _client.getSize(); }
    WiFiClient* getStreamPtr() { return _client.getStreamPtr(); }
    bool connected() { return _client.connected(); }
    int writeToStream(Stream* stream) { return _client.writeToStream(stream); }
};

#define HTTPClient Esp8266HTTPClientWrapper

#else
#include <HTTPClient.h>
#endif
