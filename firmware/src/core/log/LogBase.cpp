#include "LogBase.h"
#include "compat/fs.h"
#include <algorithm>

int LogBase::_listFiles(const char* dir, String* files, int maxCount) {
    compat::DirIterator it(dir);
    int count = 0;
    String name;
    size_t size;
    bool isDir;
    while (it.next(name, size, isDir) && count < maxCount) {
        if (name.endsWith(F(".log")) && name.length() == 14) {
            files[count++] = name;
        }
    }
    it.close();
    return count;
}

void LogBase::_sortFilesByDate(String* files, int count) {
    if (count <= 1) return;
    std::sort(files, files + count, [](const String& a, const String& b) {
        int da, ma, ya, db, mb, yb;
        if (sscanf(a.c_str(), "%d-%d-%d", &da, &ma, &ya) != 3) return false;
        if (sscanf(b.c_str(), "%d-%d-%d", &db, &mb, &yb) != 3) return true;
        if (ya != yb) return ya < yb;
        if (ma != mb) return ma < mb;
        return da < db;
    });
}

size_t LogBase::_dirBytes(const char* dir) {
    size_t total = 0;
    compat::DirIterator it(dir);
    String name;
    size_t size;
    bool isDir;
    while (it.next(name, size, isDir)) {
        total += size;
    }
    it.close();
    return total;
}

void LogBase::_appendFile(const String& src, const String& dst, size_t maxSize) {
    size_t dstSize = 0;
    {
        File fCheck = LITTLEFS.open(dst, "r");
        if (fCheck) { dstSize = fCheck.size(); fCheck.close(); }
    }
    if (maxSize > 0 && dstSize >= maxSize) return;

    File fSrc = LITTLEFS.open(src, "r");
    if (!fSrc) return;
    File fDst = LITTLEFS.open(dst, "a");
    if (!fDst) { fSrc.close(); return; }

    if (maxSize > 0) {
        size_t remaining = maxSize - dstSize;
        uint8_t buf[128];
        int n;
        while (remaining > 0 && (n = fSrc.read(buf, sizeof(buf))) > 0) {
            size_t toWrite = (size_t)n < remaining ? (size_t)n : remaining;
            fDst.write(buf, toWrite);
            remaining -= toWrite;
        }
    } else {
        uint8_t buf[128];
        int n;
        while ((n = fSrc.read(buf, sizeof(buf))) > 0) {
            fDst.write(buf, n);
        }
    }
    fSrc.close();
    fDst.close();
}

void LogBase::_clearDir(const char* dir) {
    compat::DirIterator it(dir);
    String name;
    size_t size;
    bool isDir;
    while (it.next(name, size, isDir)) {
        String p = String(dir) + name;
        LITTLEFS.remove(p);
    }
    it.close();
}

static bool _parseDate(const String& name, int& d, int& m, int& y) {
    return sscanf(name.c_str(), "%d-%d-%d", &d, &m, &y) == 3;
}

static int _dateCmp(int d1, int m1, int y1, int d2, int m2, int y2) {
    if (y1 != y2) return y1 - y2;
    if (m1 != m2) return m1 - m2;
    return d1 - d2;
}

String LogBase::_oldestFile(const char* dir) {
    compat::DirIterator it(dir);
    String found;
    int bestD = 99, bestM = 99, bestY = 9999;
    String name;
    size_t size;
    bool isDir;
    while (it.next(name, size, isDir)) {
        int dd, mm, yy;
        if (name.endsWith(F(".log")) && name.length() == 14 && _parseDate(name, dd, mm, yy)) {
            if (_dateCmp(dd, mm, yy, bestD, bestM, bestY) < 0) {
                bestD = dd; bestM = mm; bestY = yy;
                found = name;
            }
        }
    }
    it.close();
    return found.length() == 0 ? "" : String(dir) + found;
}

String LogBase::_latestFile(const char* dir) {
    compat::DirIterator it(dir);
    String found;
    int bestD = 0, bestM = 0, bestY = 0;
    String name;
    size_t size;
    bool isDir;
    while (it.next(name, size, isDir)) {
        int dd, mm, yy;
        if (name.endsWith(F(".log")) && name.length() == 14 && _parseDate(name, dd, mm, yy)) {
            if (_dateCmp(dd, mm, yy, bestD, bestM, bestY) > 0) {
                bestD = dd; bestM = mm; bestY = yy;
                found = name;
            }
        }
    }
    it.close();
    return found.length() == 0 ? "" : String(dir) + found;
}

void LogBase::_rotateBySize(const char* dir, size_t maxFolder) {
    while (_dirBytes(dir) > maxFolder) {
        String oldest = _oldestFile(dir);
        if (oldest.length() == 0) break;
        LITTLEFS.remove(oldest);
    }
}

void LogBase::_migrateNosync(const char* dir, unsigned long epoch, size_t maxFileSize) {
    time_t raw = epoch;
    struct tm ti;
    gmtime_r(&raw, &ti);
    char buf[12];
    snprintf_P(buf, sizeof(buf), PSTR("%02d-%02d-%04d"),
             ti.tm_mday, ti.tm_mon + 1, ti.tm_year + 1900);
    String dest = String(dir) + buf + F(".log");
    String src = String(dir) + F("nosync.log");

    if (!LITTLEFS.exists(src)) return;

    if (LITTLEFS.exists(dest)) {
        _appendFile(src, dest, maxFileSize);
        LITTLEFS.remove(src);
    } else {
        LITTLEFS.rename(src, dest);
    }
}

bool LogBase::_readDirConcat(const char* dir, String& out, size_t maxBytes) {
    out = "";
    compat::DirIterator it(dir);
    String name;
    size_t size;
    bool isDir;
    while (it.next(name, size, isDir)) {
        String p = String(dir) + name;
        File rf = LITTLEFS.open(p, "r");
        if (rf) {
            size_t remain = maxBytes - out.length();
            if (remain > 0) {
                uint8_t* buf = new uint8_t[remain + 1];
                size_t r = rf.read(buf, remain);
                buf[r] = 0;
                out += (const char*)buf;
                delete[] buf;
            }
            rf.close();
            if (out.length() >= maxBytes) break;
        }
    }
    it.close();
    return out.length() > 0;
}
