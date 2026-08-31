#pragma once

#include <WiFiClientSecure.h>

namespace compat {
// Reset TLS client. LibreTiny cần gọi thêm LwIPClient::stop() để đóng TCP dứt điểm (quirk SDK)
inline void tlsReset(WiFiClientSecure& client) {
    client.stop();
#if defined(LT_ARD_HAS_SERIAL)
    client.LwIPClient::stop();
#endif
    delay(10);
}


inline void setTlsBufferSize(WiFiClientSecure& client, size_t rxSize, size_t txSize = 512) {
#if defined(ARDUINO_ARCH_ESP8266)
    client.setBufferSizes(rxSize, txSize);
#else
    (void)client;
    (void)rxSize;
    (void)txSize;
#endif
}

inline bool tlsCheckHealth(WiFiClientSecure& client) {
#if defined(LT_ARD_HAS_SERIAL)
    int avail = client.available();
    if (avail < 0) {
        tlsReset(client);
        return false;
    }
#else
    (void)client;
#endif
    return true;
}

}
