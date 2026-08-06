#pragma once

#include <stddef.h>
#include <stdint.h>

// ── KV storage đa nền tảng ──
// Trả về: 0=OK, 1=key không tồn tại, 2=lỗi khác, 3=buffer quá nhỏ (storedLen = kích thước cần)
namespace compat {
int kvGet(const char* key, void* buf, size_t size, size_t* storedLen);
int kvSet(const char* key, const void* data, size_t size);
int kvDel(const char* key);
}

// ── LibreTiny: ln_kv_* (SDK) ──
#if defined(LT_ARD_HAS_SERIAL)
#include <sdk_private.h>

namespace compat {
inline int kvGet(const char* key, void* buf, size_t size, size_t* storedLen) {
    kv_err_t err = ln_kv_get(key, buf, size, storedLen);
    switch (err) {
    case KV_ERR_NONE:
        return 0;
    case KV_ERR_NOT_EXIST:
        return 1;
    case KV_ERR_BUF_TOO_SHORT:
        return 3;
    default:
        return 2;
    }
}
inline int kvSet(const char* key, const void* data, size_t size) {
    return ln_kv_set(key, data, size) == KV_ERR_NONE ? 0 : 2;
}
inline int kvDel(const char* key) {
    return ln_kv_del(key) == KV_ERR_NONE ? 0 : 2;
}
}

// ── MCU khác (ESP32): Preferences (NVS) ──
#else
#include <Preferences.h>

namespace compat {
inline int kvGet(const char* key, void* buf, size_t size, size_t* storedLen) {
    Preferences prefs;
    if (!prefs.begin("app", true)) return 2;
    PreferenceType t = prefs.getType(key);
    if (t != PT_BLOB) {
        prefs.end();
        return (t == PT_INVALID) ? 1 : 2;
    }
    size_t got = prefs.getBytes(key, buf, size);
    prefs.end();
    if (storedLen) *storedLen = got;
    if (got > size) return 3;
    return 0;
}
inline int kvSet(const char* key, const void* data, size_t size) {
    Preferences prefs;
    if (!prefs.begin("app", false)) return 2;
    bool ok = prefs.putBytes(key, data, size);
    prefs.end();
    return ok ? 0 : 2;
}
inline int kvDel(const char* key) {
    Preferences prefs;
    if (!prefs.begin("app", false)) return 2;
    bool ok = prefs.remove(key);
    prefs.end();
    return ok ? 0 : 2;
}
}
#endif
