#pragma once

#include <stddef.h>
#include <stdint.h>

// ── KV storage đa nền tảng ──
enum class KvError : int {
    Ok = 0,          // thành công
    NotExist = 1,    // key không tồn tại
    Other = 2,       // lỗi khác
    BufTooShort = 3, // buffer quá nhỏ (storedLen = kích thước cần)
};

namespace compat {
KvError kvGet(const char* key, void* buf, size_t size, size_t* storedLen);
KvError kvSet(const char* key, const void* data, size_t size);
KvError kvDel(const char* key);
}

// ── LibreTiny: ln_kv_* (SDK) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>

namespace compat {
inline KvError kvGet(const char* key, void* buf, size_t size, size_t* storedLen) {
    kv_err_t err = ln_kv_get(key, buf, size, storedLen);
    switch (err) {
    case KV_ERR_NONE:
        return KvError::Ok;
    case KV_ERR_NOT_EXIST:
        return KvError::NotExist;
    case KV_ERR_BUF_TOO_SHORT:
        return KvError::BufTooShort;
    default:
        return KvError::Other;
    }
}
inline KvError kvSet(const char* key, const void* data, size_t size) {
    return ln_kv_set(key, data, size) == KV_ERR_NONE ? KvError::Ok : KvError::Other;
}
inline KvError kvDel(const char* key) {
    return ln_kv_del(key) == KV_ERR_NONE ? KvError::Ok : KvError::Other;
}
}

// ── MCU khác (ESP8266): LittleFS KV ──
#elif defined(ARDUINO_ARCH_ESP8266)
#include <LittleFS.h>

namespace compat {
inline KvError kvGet(const char* key, void* buf, size_t size, size_t* storedLen) {
    String path = String("/kv/") + key;
    if (!LittleFS.exists(path)) return KvError::NotExist;
    File f = LittleFS.open(path, "r");
    if (!f) return KvError::Other;
    size_t totalLen = f.size();
    f.read((uint8_t*)buf, size);
    f.close();
    if (storedLen) *storedLen = totalLen;
    if (totalLen > size) return KvError::BufTooShort;
    return KvError::Ok;
}

inline KvError kvSet(const char* key, const void* data, size_t size) {
    if (!LittleFS.exists("/kv")) LittleFS.mkdir("/kv");
    String path = String("/kv/") + key;
    File f = LittleFS.open(path, "w");
    if (!f) return KvError::Other;
    size_t written = f.write((const uint8_t*)data, size);
    f.close();
    return written == size ? KvError::Ok : KvError::Other;
}

inline KvError kvDel(const char* key) {
    String path = String("/kv/") + key;
    return LittleFS.remove(path) ? KvError::Ok : KvError::NotExist;
}
}

// ── MCU khác (ESP32): Preferences (NVS) ──
#else
#include <Preferences.h>

namespace compat {
inline KvError kvGet(const char* key, void* buf, size_t size, size_t* storedLen) {
    Preferences prefs;
    if (!prefs.begin("app", true)) return KvError::Other;
    PreferenceType t = prefs.getType(key);
    if (t != PT_BLOB) {
        prefs.end();
        return (t == PT_INVALID) ? KvError::NotExist : KvError::Other;
    }
    size_t got = prefs.getBytes(key, buf, size);
    prefs.end();
    if (storedLen) *storedLen = got;
    if (got > size) return KvError::BufTooShort;
    return KvError::Ok;
}
inline KvError kvSet(const char* key, const void* data, size_t size) {
    Preferences prefs;
    if (!prefs.begin("app", false)) return KvError::Other;
    bool ok = prefs.putBytes(key, data, size);
    prefs.end();
    return ok ? KvError::Ok : KvError::Other;
}
inline KvError kvDel(const char* key) {
    Preferences prefs;
    if (!prefs.begin("app", false)) return KvError::Other;
    bool ok = prefs.remove(key);
    prefs.end();
    return ok ? KvError::Ok : KvError::Other;
}
}
#endif
