#include "OTAManager.h"
#include <Update.h>
#include <HTTPClient.h>
#include <WiFi.h>
#include <algorithm>

OTAManager::OTAManager()
    : _running(false)
    , _urlMode(false)
    , _progress(0)
    , _total(0)
    , _written(0)
{
}

bool OTAManager::begin() {
    return true;
}

void OTAManager::handle() {
    if (!_running || !_urlMode) return;
}

bool OTAManager::startFromUrl(const String& url, ProgressCallback onProgress, ResultCallback onResult) {
    if (_running) return false;

    _urlMode = true;
    _onProgress = onProgress;
    _onResult = onResult;
    _progress = 0;
    _written = 0;

    HTTPClient http;
    http.begin(url);
    http.setFollowRedirects(HTTPC_FORCE_FOLLOW_REDIRECTS);
    http.addHeader("User-Agent", "LN882H-OTA/1.0");

    int httpCode = http.GET();
    if (httpCode != HTTP_CODE_OK) {
        if (_onResult) _onResult(false, "HTTP error");
        http.end();
        return false;
    }

    _total = http.getSize();
    if (_total <= 0) _total = 1048576; // 1MB default

    if (!Update.begin(_total, U_FLASH)) {
        if (_onResult) _onResult(false, Update.errorString());
        http.end();
        return false;
    }

    _running = true;
    WiFiClient* stream = http.getStreamPtr();
    uint8_t buf[OTA_CHUNK_SIZE];

    while (http.connected() && _running) {
        size_t available = stream->available();
        if (available > 0) {
            size_t toRead = std::min(available, (size_t)OTA_CHUNK_SIZE);
            size_t read = stream->readBytes(buf, toRead);
            size_t written = Update.write(buf, read);
            _written += written;
            _progress = _written;

            if (_onProgress) _onProgress(_progress, _total);
        } else {
            delay(10);
        }

        if (_written >= (size_t)_total) break;
    }

    bool success = Update.end(true);
    if (success) {
        if (_onResult) _onResult(true, "OTA success! Rebooting...");
    } else {
        if (_onResult) _onResult(false, Update.errorString());
    }

    http.end();
    _running = false;

    if (success) {
        delay(500);
        ESP.restart();
    }
    return success;
}

bool OTAManager::startFromStream(size_t size, ProgressCallback onProgress, ResultCallback onResult) {
    Update.abort();

    _urlMode = false;
    _onProgress = onProgress;
    _onResult = onResult;
    _total = size;
    _progress = 0;
    _written = 0;

    if (!Update.begin(size, U_FLASH)) {
        if (_onResult) _onResult(false, Update.errorString());
        return false;
    }

    _running = true;
    return true;
}

bool OTAManager::writeChunk(const uint8_t* data, size_t len) {
    if (!_running || _urlMode) return false;

    size_t written = Update.write(const_cast<uint8_t*>(data), len);
    _written += written;
    _progress = _written;
    if (_onProgress) _onProgress(_progress, _total);
    return written == len;
}

bool OTAManager::end() {
    if (!_running) return false;
    _running = false;

    bool success = Update.end(true);
    if (_onResult) {
        if (success) _onResult(true, "OTA success! Rebooting...");
        else _onResult(false, Update.errorString());
    }

    if (success) {
        delay(500);
        ESP.restart();
    }
    return success;
}

bool OTAManager::abort() {
    if (!_running) return false;
    _running = false;
    Update.abort();
    if (_onResult) _onResult(false, "OTA aborted");
    return true;
}

void OTAManager::writeError() {
    if (_onResult) _onResult(false, Update.errorString());
    Update.abort();
    _running = false;
}

bool OTAManager::canRollback() {
    return Update.canRollBack();
}

bool OTAManager::rollback() {
    if (!Update.canRollBack()) return false;
    Update.rollBack();
    delay(500);
    ESP.restart();
    return true;
}
