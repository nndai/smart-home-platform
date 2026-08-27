#include "core/FileBrowser.h"
#include "compat/fs.h"
#include "core/Crypto.h"
#include <ArduinoJson.h>
#include <vector>
#include <algorithm>

String FileBrowser::listDir(const String& path, size_t offset, size_t limit) {
    JsonDocument doc;
    doc[F("status")] = F("ok");
    doc[F("path")] = path;
    //LT_IM(SYS, "Listing directory: %s", path.c_str());
    File dir = LITTLEFS.open(path, "r");
    if (!dir) {
        doc[F("status")] = F("error");
        doc[F("message")] = F("Not a directory");
        String out;
        serializeJson(doc, out);
        return out;
    }

    if (limit == 0) {
        JsonArray entries = doc[F("entries")].to<JsonArray>();
        compat::DirIterator it(path.c_str());
        String name;
        size_t size;
        bool isDir;
        while (it.next(name, size, isDir)) {
            JsonObject e = entries.add<JsonObject>();
            e[F("name")] = name;
            if (isDir) {
                e[F("type")] = F("dir");
            } else {
                e[F("type")] = F("file");
                e[F("size")] = (unsigned long)size;
            }
        }
        it.close();
        String out;
        serializeJson(doc, out);
        return out;
    }

    struct DirEntry {
        String name;
        String type;
        unsigned long size;
    };
    std::vector<DirEntry> all;
    {
        compat::DirIterator it(path.c_str());
        String name;
        size_t size;
        bool isDir;
        while (it.next(name, size, isDir)) {
            DirEntry e;
            e.name = name;
            if (isDir) {
                e.type = F("dir");
                e.size = 0;
            } else {
                e.type = F("file");
                e.size = (unsigned long)size;
            }
            all.push_back(e);
        }
        it.close();
    }

    std::sort(all.begin(), all.end(), [](const DirEntry& a, const DirEntry& b) {
        int da, ma, ya, db, mb, yb;
        bool aOk = sscanf(a.name.c_str(), "%d-%d-%d", &da, &ma, &ya) == 3;
        bool bOk = sscanf(b.name.c_str(), "%d-%d-%d", &db, &mb, &yb) == 3;
        if (!aOk && !bOk) return a.name < b.name;
        if (!aOk) return true;
        if (!bOk) return false;
        if (ya != yb) return ya < yb;
        if (ma != mb) return ma < mb;
        return da < db;
    });

    size_t total = all.size();
    doc[F("total")] = (unsigned long)total;

    if (offset >= total) {
        doc[F("entries")] = JsonArray();
        doc[F("more")] = false;
    } else {
        size_t end = offset + limit;
        if (end > total) end = total;
        doc[F("more")] = (end < total);
        JsonArray entries = doc[F("entries")].to<JsonArray>();
        for (size_t i = offset; i < end; i++) {
            JsonObject e = entries.add<JsonObject>();
            e[F("name")] = all[i].name;
            e[F("type")] = all[i].type;
            if (all[i].type == F("file")) {
                e[F("size")] = all[i].size;
            }
        }
    }

    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::readFile(const String& path, size_t offset, size_t limit, bool encode) {
    JsonDocument doc;
    doc[F("status")] = F("ok");
    doc[F("path")] = path;
    doc[F("encode")] = encode;
    doc[F("offset")] = (unsigned long)offset;

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        doc[F("status")] = F("error");
        doc[F("message")] = F("File not found");
        String out;
        serializeJson(doc, out);
        return out;
    }

    size_t fileSize = f.size();
    doc[F("size")] = (unsigned long)fileSize;

    if (offset >= fileSize) {
        doc[F("data")] = F("");
        doc[F("more")] = false;
        f.close();
        String out;
        serializeJson(doc, out);
        return out;
    }

    f.seek(offset, SeekSet);

    size_t toRead = limit;
    if (offset + toRead > fileSize) {
        toRead = fileSize - offset;
    }

    if (encode) {
        uint8_t* buf = (uint8_t*)malloc(toRead);
        if (!buf) {
            doc[F("status")] = F("error");
            doc[F("message")] = F("Out of memory");
            f.close();
            String out;
            serializeJson(doc, out);
            return out;
        }
        size_t n = f.read(buf, toRead);
        doc[F("data")] = crypto::base64Encode(buf, n);
        free(buf);
        doc[F("more")] = (offset + n < fileSize);
    } else {
        char buf[128];
        String data;
        data.reserve(toRead + 64);
        size_t remaining = toRead;
        while (remaining > 0) {
            size_t n = f.read((uint8_t*)buf, std::min<size_t>(sizeof(buf) - 1, remaining));
            if (n == 0) break;
            buf[n] = '\0';
            data += buf;
            remaining -= n;
        }
        doc[F("data")] = data;
        doc[F("more")] = (offset + data.length() < fileSize);
    }

    f.close();
    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::fileInfo(const String& path) {
    JsonDocument doc;
    doc[F("path")] = path;

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        doc[F("status")] = F("error");
        doc[F("message")] = F("Not found");
        String out;
        serializeJson(doc, out);
        return out;
    }

    doc[F("status")] = F("ok");
    doc[F("type")] = f.isDirectory() ? F("dir") : F("file");
    doc[F("size")] = (unsigned long)f.size();
    f.close();

    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::deleteItem(const String& path) {
    JsonDocument doc;
    doc[F("path")] = path;

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        doc[F("status")] = F("error");
        doc[F("message")] = F("Not found");
        String out;
        serializeJson(doc, out);
        return out;
    }
    bool isDir = f.isDirectory();
    f.close();

    bool ok;
    if (isDir) {
        // Remove all files inside first
        compat::DirIterator it(path.c_str());
        String name;
        size_t size;
        bool childIsDir;
        while (it.next(name, size, childIsDir)) {
            String childPath = path + "/" + name;
            LITTLEFS.remove(childPath);
        }
        it.close();
        ok = LITTLEFS.rmdir(path);
    } else {
        ok = LITTLEFS.remove(path);
    }

    if (ok) {
        doc[F("status")] = F("ok");
    } else {
        doc[F("status")] = F("error");
        doc[F("message")] = F("Delete failed");
    }

    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::fsInfo() {
    JsonDocument doc;

    doc[F("status")] = F("ok");
    doc[F("totalBytes")] = (unsigned long)compat::fsTotalBytes();
    doc[F("usedBytes")] = (unsigned long)compat::fsUsedBytes();

    String out;
    serializeJson(doc, out);
    return out;
}


