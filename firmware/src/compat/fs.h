#pragma once

// ── Filesystem đa nền tảng ──
#if defined(LT_ARD_HAS_SERIAL)
#include <LittleFS.h>
namespace compat {
inline size_t fsTotalBytes() { return LITTLEFS.totalBytes(); }
inline size_t fsUsedBytes() { return LITTLEFS.usedBytes(); }
}
#elif defined(ARDUINO_ARCH_ESP8266)
#include <LittleFS.h>
using namespace fs;
#define LITTLEFS LittleFS
namespace compat {
inline size_t fsTotalBytes() {
    FSInfo info;
    LittleFS.info(info);
    return info.totalBytes;
}
inline size_t fsUsedBytes() {
    FSInfo info;
    LittleFS.info(info);
    return info.usedBytes;
}
}
#else
#include <LittleFS.h>
using namespace fs;
#define LITTLEFS LittleFS
namespace compat {
inline size_t fsTotalBytes() { return LITTLEFS.totalBytes(); }
inline size_t fsUsedBytes() { return LITTLEFS.usedBytes(); }
}
#endif

namespace compat {

class DirIterator {
#if defined(ARDUINO_ARCH_ESP8266)
    Dir _dir;
public:
    DirIterator(const char* path) : _dir(LittleFS.openDir(path)) {}
    bool next(String& outName, size_t& outSize, bool& outIsDir) {
        if (!_dir.next()) return false;
        outName = _dir.fileName();
        outSize = _dir.fileSize();
        outIsDir = _dir.isDirectory();
        return true;
    }
    void close() {}
#else
    File _d;
    File _cur;
public:
    DirIterator(const char* path) : _d(LITTLEFS.open(path, "r")) {}
    bool next(String& outName, size_t& outSize, bool& outIsDir) {
        if (!_d || !_d.isDirectory()) return false;
        _cur = _d.openNextFile();
        if (!_cur) return false;
        outName = String(_cur.name());
        outSize = _cur.size();
        outIsDir = _cur.isDirectory();
        _cur.close();
        return true;
    }
    void close() { if (_d) _d.close(); }
#endif
};

}
