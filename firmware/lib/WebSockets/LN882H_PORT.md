# LN882H Port — WebSockets-Links2004

## Tổng quan
Port thư viện WebSockets-Links2004 để hỗ trợ vi điều khiển **LN882H** (lõi ARM, nền tảng LibreTiny).

Không dùng SSL/TLS. Cấu hình tương tự ESP32.

---

## 1. `src/WebSockets.h` — 4 khối thêm mới

### 1a. Platform macros (dòng 114–120)
Thêm sau block `WIO_TERMINAL`, trước `#else` (atmega328p):

```cpp
#elif defined(LT_LN882H) || defined(ARDUINO_ARCH_LN882H) || defined(LT_ARCH_LN882H)

#define WEBSOCKETS_MAX_DATA_SIZE (15 * 1024)
#define WEBSOCKETS_USE_BIG_MEM
#define GET_FREE_HEAP ESP.getFreeHeap()
#define WEBSOCKETS_YIELD() yield()
#define WEBSOCKETS_YIELD_MORE() delay(1)
```

### 1b. Network type define (dòng 147)
Thêm sau `NETWORK_CUSTOM (10)`:

```cpp
#define NETWORK_LN882H (11)
```

### 1c. Auto-detect network type (dòng 175–176)
Thêm sau `SAMD_SEED`, trước `#else`:

```cpp
#elif defined(LT_LN882H) || defined(ARDUINO_ARCH_LN882H) || defined(LT_ARCH_LN882H)
#define WEBSOCKETS_NETWORK_TYPE NETWORK_LN882H
```

### 1d. Network type implementation (dòng 275–279)
Thêm sau block `RP2040`, trước `UNOWIFIR4`:

```cpp
#elif (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)

#include <WiFi.h>
#define WEBSOCKETS_NETWORK_CLASS WiFiClient
#define WEBSOCKETS_NETWORK_SERVER_CLASS WiFiServer
```

---

## 2. `src/WebSocketsServer.h` — 1 dòng thêm

### 2a. `remoteIP()` method guard (dòng 93)
Thêm `|| (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)` vào `#if`:

```cpp
#if (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266) || \
    (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266_ASYNC) || \
    (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP32) || \
    (WEBSOCKETS_NETWORK_TYPE == NETWORK_RP2040) || \
    (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)     // <-- thêm
    IPAddress remoteIP(uint8_t num);
#endif
```

---

## 3. `src/WebSocketsServer.cpp` — 5 dòng thêm

### 3a. `remoteIP()` implementation guard (dòng 414)
```cpp
// Trước:
#if (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266_ASYNC) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP32) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_RP2040)
// Sau:                                                              thêm →                          || (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)
```

### 3b. `newClient()` debug IP log guard (dòng 467)
```cpp
// Trước:
#if (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266_ASYNC) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP32) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_RP2040)
// Sau:                                                              thêm →                          || (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)
```

### 3c. `handleNewClient()` debug IP guard (dòng 645)
```cpp
// Trước:
#if (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP32) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_RP2040)
// Sau:                                                              thêm →                          || (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)
```

### 3d. `handleNewClients()` hasClient loop guard (dòng 670 + 688)
```cpp
// Trước (cả 2 dòng):
#if (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP32) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_RP2040)
// Sau:                                                              thêm →                          || (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)
```

### 3e. `close()` method guard (dòng 959)
```cpp
// Trước:
#if (WEBSOCKETS_NETWORK_TYPE == NETWORK_ESP8266) || (WEBSOCKETS_NETWORK_TYPE == NETWORK_RP2040)
// Sau:                                                                                                    || (WEBSOCKETS_NETWORK_TYPE == NETWORK_LN882H)
```

---

## Cấu hình cho LN882H

| Config | Giá trị | Lý do |
|--------|---------|-------|
| `WEBSOCKETS_MAX_DATA_SIZE` | `(15 * 1024)` = 15KB | LN882H có 295KB RAM, dư để chứa OTA chunk |
| `WEBSOCKETS_USE_BIG_MEM` | defined | Gộp header+payload vào 1 TCP packet nếu heap >6KB |
| `GET_FREE_HEAP` | `ESP.getFreeHeap()` → `lt_heap_get_free()` | Có sẵn trong LibreTiny core |
| `WEBSOCKETS_YIELD` | `yield()` | Nhường CPU cho FreeRTOS |
| `WEBSOCKETS_YIELD_MORE` | `delay(1)` | Giống ESP32 |
| `WEBSOCKETS_TCP_TIMEOUT` | 5000ms (default) | Timeout đọc/ghi TCP |
| `WEBSOCKETS_SERVER_CLIENT_MAX` | 5 (default) | Số client tối đa |
| SSL | không define | Project không dùng SSL |

---

## Những thứ KHÔNG cần sửa

| File / Dòng | Lý do |
|-------------|-------|
| `WebSocketsClient.cpp` line 966 (`setNoDelay`) | `LwIPClient` không có `setNoDelay()`. Project không dùng client mode. |
| `WebSocketsServer.cpp` line 550 (flush guard) | LN882H có `flush()` → mặc định được gọi (không bị loại trừ như ESP32/RP2040) |
| `WebSocketsServer.cpp` line 571 (SSL guard) | LN882H không có `HAS_SSL` → không vào block SSL → an toàn |
| `WebSocketsServer.cpp` line 458 (`setNoDelay` trên client) | `LwIPClient` không có `setNoDelay()`. Chỉ ESP8266/ESP32 mới vào block này. |

---

## Lưu ý khi tích hợp vào main (bước sau)

1. **Gọi `_server->setNoDelay(true)`** trong `WebSocketServer::begin()` — `LwIPServer::accept()` dùng `_noDelay` để set TCP_NODELAY; mặc định `false`.

2. **API thay đổi** so với `ArduinoWebsockets`:
   - `wsServer.handle()` → `wsServer.loop()`
   - `wsServer.send(clientId, msg)` → `_server.sendTXT(clientNum, msg)`
   - `wsServer.broadcast(msg)` → `_server.broadcastTXT(msg)`
   - Callback: `(uint8_t num, WStype_t type, uint8_t* payload, size_t length)`

3. **Binary data**: `WStype_BIN` gửi toàn bộ payload (nhờ `readCb()` loop). Không cần xử lý chunk.

---
*Port by opencode — Tháng 7, 2026*
