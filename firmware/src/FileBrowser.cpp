#include "FileBrowser.h"
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <mbedtls/base64.h>
#include <vector>
#include <algorithm>

String FileBrowser::listDir(const String& path, size_t offset, size_t limit) {
    JsonDocument doc;
    doc["status"] = "ok";
    doc["path"] = path;
    //LT_IM(SYS, "Listing directory: %s", path.c_str());
    File dir = LITTLEFS.open(path);
    if (!dir || !dir.isDirectory()) {
        doc["status"] = "error";
        doc["message"] = "Not a directory";
        String out;
        serializeJson(doc, out);
        return out;
    }

    if (limit == 0) {
        JsonArray entries = doc["entries"].to<JsonArray>();
        File f = dir.openNextFile();
        while (f) {
            JsonObject e = entries.add<JsonObject>();
            e["name"] = String(f.name());
            if (f.isDirectory()) {
                e["type"] = "dir";
            } else {
                e["type"] = "file";
                e["size"] = (unsigned long)f.size();
            }
            f.close();
            f = dir.openNextFile();
        }
        dir.close();
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
        File f = dir.openNextFile();
        while (f) {
            DirEntry e;
            e.name = String(f.name());
            if (f.isDirectory()) {
                e.type = "dir";
                e.size = 0;
            } else {
                e.type = "file";
                e.size = (unsigned long)f.size();
            }
            all.push_back(e);
            f.close();
            f = dir.openNextFile();
        }
    }
    dir.close();

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
    doc["total"] = (unsigned long)total;

    if (offset >= total) {
        doc["entries"] = JsonArray();
        doc["more"] = false;
    } else {
        size_t end = offset + limit;
        if (end > total) end = total;
        doc["more"] = (end < total);
        JsonArray entries = doc["entries"].to<JsonArray>();
        for (size_t i = offset; i < end; i++) {
            JsonObject e = entries.add<JsonObject>();
            e["name"] = all[i].name;
            e["type"] = all[i].type;
            if (all[i].type == "file") {
                e["size"] = all[i].size;
            }
        }
    }

    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::readFile(const String& path, size_t offset, size_t limit, bool encode) {
    JsonDocument doc;
    doc["status"] = "ok";
    doc["path"] = path;
    doc["encode"] = encode;
    doc["offset"] = (unsigned long)offset;

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        doc["status"] = "error";
        doc["message"] = "File not found";
        String out;
        serializeJson(doc, out);
        return out;
    }

    size_t fileSize = f.size();
    doc["size"] = (unsigned long)fileSize;

    if (offset >= fileSize) {
        doc["data"] = "";
        doc["more"] = false;
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
            doc["status"] = "error";
            doc["message"] = "Out of memory";
            f.close();
            String out;
            serializeJson(doc, out);
            return out;
        }
        size_t n = f.read(buf, toRead);

        size_t olen = 0;
        mbedtls_base64_encode(NULL, 0, &olen, buf, n);
        uint8_t* b64 = (uint8_t*)malloc(olen + 1);
        if (b64) {
            mbedtls_base64_encode(b64, olen + 1, &olen, buf, n);
            b64[olen] = '\0';
            doc["data"] = (const char*)b64;
            free(b64);
        } else {
            doc["data"] = "";
        }
        free(buf);
        doc["more"] = (offset + n < fileSize);
    } else {
        char buf[128];
        String data;
        data.reserve(toRead + 64);
        size_t remaining = toRead;
        while (remaining > 0) {
            size_t n = f.read((uint8_t*)buf, min(sizeof(buf) - 1, remaining));
            if (n == 0) break;
            buf[n] = '\0';
            data += buf;
            remaining -= n;
        }
        doc["data"] = data;
        doc["more"] = (offset + data.length() < fileSize);
    }

    f.close();
    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::fileInfo(const String& path) {
    JsonDocument doc;
    doc["path"] = path;

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        doc["status"] = "error";
        doc["message"] = "Not found";
        String out;
        serializeJson(doc, out);
        return out;
    }

    doc["status"] = "ok";
    doc["type"] = f.isDirectory() ? "dir" : "file";
    doc["size"] = (unsigned long)f.size();
    f.close();

    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::deleteItem(const String& path) {
    JsonDocument doc;
    doc["path"] = path;

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        doc["status"] = "error";
        doc["message"] = "Not found";
        String out;
        serializeJson(doc, out);
        return out;
    }
    bool isDir = f.isDirectory();
    f.close();

    bool ok;
    if (isDir) {
        // Remove all files inside first
        File d = LITTLEFS.open(path);
        if (d) {
            File child = d.openNextFile();
            while (child) {
                String childPath = path + "/" + String(child.name());
                child.close();
                LITTLEFS.remove(childPath);
                child = d.openNextFile();
            }
            d.close();
        }
        ok = LITTLEFS.rmdir(path);
    } else {
        ok = LITTLEFS.remove(path);
    }

    if (ok) {
        doc["status"] = "ok";
    } else {
        doc["status"] = "error";
        doc["message"] = "Delete failed";
    }

    String out;
    serializeJson(doc, out);
    return out;
}

String FileBrowser::fsInfo() {
    JsonDocument doc;

    doc["status"] = "ok";
    doc["totalBytes"] = (unsigned long)LITTLEFS.totalBytes();
    doc["usedBytes"] = (unsigned long)LITTLEFS.usedBytes();

    String out;
    serializeJson(doc, out);
    return out;
}


