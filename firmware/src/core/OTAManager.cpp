#include "core/OTAManager.h"
#include "compat/http.h"
#include "compat/ota.h"
#include "compat/tls.h"
#include "compat/wdt.h"
#include "compat/wifi.h"
#include <WiFiClientSecure.h>
#include <algorithm>

OTAManager::OTAManager()
    : _running(false), _urlMode(false), _hasPending(false), _pendingUrl(""),
    _progress(0), _total(0), _written(0) {
}

bool OTAManager::begin() { return true; }

void OTAManager::queueUrl(const String& url, ProgressCallback onProgress,
    ResultCallback onResult) {
    if (_running)
        return;
    _pendingUrl = url;
    _onProgress = onProgress;
    _onResult = onResult;
    _hasPending = true;
}

void OTAManager::handle() {
    if (_hasPending && !_running) {
        _hasPending = false;
        String targetUrl = _pendingUrl;
        _pendingUrl = "";
        startFromUrl(targetUrl, _onProgress, _onResult);
    }
}

bool OTAManager::startFromUrl(const String& url, ProgressCallback onProgress, ResultCallback onResult) {
    if (_running) return false;

    _urlMode = true;
    _onProgress = onProgress;
    _onResult = onResult;
    _progress = 0;
    _written = 0;
    _total = 0;

    delay(100);
    yield();
    compat::wdtFeed();

    _running = true;
    uint32_t offset = 0;
    const uint32_t RANGE_BLOCK_SIZE = 100 * 1024; // 100 KB per HTTP Range block request
    uint8_t* readBuf = new uint8_t[OTA_CHUNK_SIZE];
    bool success = true;
    bool updateStarted = false;
    int retryCount = 0;

    WiFiClient* client = nullptr;
    if (url.startsWith("https://")) {
        WiFiClientSecure* secureClient = new WiFiClientSecure();
        secureClient->setInsecure();
        compat::setTlsBufferSize(*secureClient, 3000, 512);
        secureClient->setTimeout(15000);
        client = secureClient;
    }
    else {
        client = new WiFiClient();
        client->setTimeout(15000);
    }

    while ((_total == 0 || offset < (uint32_t)_total) && _running) {
        yield();
        compat::wdtFeed();

        uint32_t end =
            (_total == 0)
            ? (offset + RANGE_BLOCK_SIZE - 1)
            : std::min(offset + RANGE_BLOCK_SIZE - 1, (uint32_t)(_total - 1));

        HTTPClient http;
        http.begin(*client, url);
        http.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
        http.addHeader(F("Accept-Encoding"), F("identity"));

        const char* headerKeys[] = { "Content-Range", "Content-Length" };
        http.collectHeaders(headerKeys, 2);

        String range = "bytes=" + String(offset) + "-" + String(end);
        http.addHeader("Range", range);

        yield();
        compat::wdtFeed();

        int code = http.GET();

        if (code == 416) { // Range Not Satisfiable (reached EOF)
            http.end();
            break;
        }

        if (code != HTTP_CODE_PARTIAL_CONTENT && code != HTTP_CODE_OK) {
            http.end();
            retryCount++;
            if (retryCount >= 3) {
                success = false;
                if (_onResult)
                    _onResult(false, ("HTTP error: " + String(code)).c_str());
                break;
            }
            delay(1000);
            continue;
        }

        if (code == HTTP_CODE_OK && offset > 0) {
            success = false;
            http.end();
            if (_onResult)
                _onResult(false, "Server does not support Range");
            break;
        }

        retryCount = 0;
        WiFiClient* stream = http.getStreamPtr();
        uint32_t remaining = end - offset + 1;

        if (code == HTTP_CODE_OK) {
            int size = http.getSize();
            if (size > 0) {
                remaining = size;
                _total = size;
            }
        }
        else if (code == HTTP_CODE_PARTIAL_CONTENT) {
            int size = http.getSize();
            if (size > 0) {
                remaining = size;
            }
            if (_total == 0) {
                String contentRange = http.header("Content-Range");
                int slashIdx = contentRange.indexOf('/');
                if (slashIdx > 0) {
                    _total = contentRange.substring(slashIdx + 1).toInt();
                }
            }
        }

        // Bắt buộc phải có total size từ header, không có thì báo lỗi ngay
        if (_total <= 0) {
            success = false;
            http.end();
            if (_onResult)
                _onResult(false, "Missing total firmware size in HTTP header");
            break;
        }

        if (!updateStarted) {
            if (!Update.begin((size_t)_total, U_FLASH)) {
                success = false;
                http.end();
                if (_onResult)
                    _onResult(false, Update.errorString());
                break;
            }
            updateStarted = true;
        }

        uint32_t lastDataTime = millis();
        while (remaining > 0 && _running && http.connected()) {
            yield();
            compat::wdtFeed();

            size_t available = stream->available();
            if (available > 0) {
                size_t toRead =
                    std::min((size_t)available,
                        std::min((size_t)OTA_CHUNK_SIZE, (size_t)remaining));
                int read = stream->read(readBuf, toRead);

                if (read > 0) {
                    size_t written = Update.write(readBuf, read);
                    _written += written;
                    _progress = _written;
                    offset += read;
                    remaining -= read;
                    lastDataTime = millis();

                    if (_onProgress)
                        _onProgress(_progress, _total);

                    if (written != (size_t)read) {
                        success = false;
                        if (_onResult)
                            _onResult(false, Update.errorString());
                        break;
                    }
                }
            }
            else {
                if (millis() - lastDataTime > 10000) {
                    break;
                }
                delay(1);
            }
        }

        http.end();

        if (!success)
            break;
        if (code == HTTP_CODE_OK)
            break;
    }

    delete client;
    delete[] readBuf;

    yield();
    compat::wdtFeed();

    if (success && _written > 0 && updateStarted) {
        success = Update.end(true);
        if (success) {
            if (_onResult)
                _onResult(true, "OTA success! Rebooting...");
        }
        else {
            if (_onResult)
                _onResult(false, Update.errorString());
        }
    }
    else if (updateStarted) {
        success = false;
        if (_onResult && _written == 0)
            _onResult(false, "No data written");
        Update.abort();
    }

    _running = false;

    if (success) {
        delay(500);
        ESP.restart();
    }
    return success;
}

bool OTAManager::startFromStream(size_t size, ProgressCallback onProgress,
    ResultCallback onResult) {
    Update.abort();

    _urlMode = false;
    _onProgress = onProgress;
    _onResult = onResult;
    _total = size;
    _progress = 0;
    _written = 0;

    if (!Update.begin(size, U_FLASH)) {
        if (_onResult)
            _onResult(false, Update.errorString());
        return false;
    }

    _running = true;
    return true;
}

bool OTAManager::writeChunk(const uint8_t* data, size_t len) {
    if (!_running || _urlMode)
        return false;

    size_t written = Update.write(const_cast<uint8_t*>(data), len);
    _written += written;
    _progress = _written;
    if (_onProgress)
        _onProgress(_progress, _total);
    return written == len;
}

bool OTAManager::end() {
    if (!_running)
        return false;
    _running = false;

    bool success = Update.end(true);
    if (_onResult) {
        if (success)
            _onResult(true, "OTA success! Rebooting...");
        else
            _onResult(false, Update.errorString());
    }

    if (success) {
        delay(500);
        ESP.restart();
    }
    return success;
}

bool OTAManager::abort() {
    if (!_running)
        return false;
    _running = false;
    Update.abort();
    if (_onResult)
        _onResult(false, "OTA aborted");
    return true;
}

void OTAManager::writeError() {
    if (_onResult)
        _onResult(false, Update.errorString());
    Update.abort();
    _running = false;
}

bool OTAManager::canRollback() { return Update.canRollBack(); }

bool OTAManager::rollback() {
    if (!Update.canRollBack())
        return false;
    Update.rollBack();
    delay(500);
    ESP.restart();
    return true;
}
