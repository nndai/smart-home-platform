#include "FileBrowser.h"
#include "compat/fs.h"
#include <algorithm>

namespace {
bool allowedPath(const String& path, bool listing = false) {
    if (!path.startsWith("/") || path.indexOf("..") >= 0 || path.indexOf('\\') >= 0 ||
        path.indexOf("//") >= 0 || path.length() > 120) return false;
    if (listing && (path == "/" || path == "/logs" || path == "/logs/")) return true;
    return path.startsWith("/logs/power/") || path.startsWith("/logs/toggle/") ||
        path.startsWith("/logs/sys/") || path == "/logs/power" || path == "/logs/toggle" || path == "/logs/sys";
}
bool deny(protocol::CommandResponse& response, const String& path, bool listing = false) {
    if (allowedPath(path, listing)) return false;
    response.setString(protocol::FieldId::Status, "invalid_path");
    response.setString(protocol::FieldId::Message, "Only legacy log paths are exposed; use the log API for v2");
    return true;
}
}

void FileBrowser::listDir(const String& path, size_t offset, size_t limit, protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Path, path);
    if (deny(resp, path, true)) return;
    if (offset > 4096) {
        resp.setString(protocol::FieldId::Status, "invalid_cursor");
        return;
    }
    limit = std::min(limit ? limit : size_t(20), size_t(20));
    resp.setString(protocol::FieldId::Status, "ok");
    auto entries = resp.beginArray(protocol::FieldId::Entries);
    compat::DirIterator it(path.c_str());
    String name; size_t size; bool dir;
    size_t visited = 0, emitted = 0;
    bool more = false;
    // Offset pagination still scans the prefix, but RAM and response size stay bounded.
    while (it.next(name, size, dir)) {
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name == "v2") continue;
        if (path == "/" && name != "logs") continue;
        if (visited++ < offset) continue;
        if (emitted >= limit) { more = true; break; }
        auto e = entries->addBeginObject();
        e->setString(protocol::FieldId::Name, name);
        e->setString(protocol::FieldId::Type, dir ? "dir" : "file");
        if (!dir) e->setU32(protocol::FieldId::Size, size);
        entries->endObject(e); ++emitted;
    }
    it.close(); resp.endArray(entries);
    resp.setBool(protocol::FieldId::More, more);
    // Total is unknown without a full scan. Clients should follow More.
    resp.setU32(protocol::FieldId::Total, more ? 0 : offset + emitted);
}

void FileBrowser::readFile(const String& path, size_t offset, size_t limit, protocol::CommandResponse& resp) {
    if (deny(resp, path)) return;
    if (limit == 0 || limit > 1500) {
        limit = 1500;
    }
    resp.setString(protocol::FieldId::Path, path);
    resp.setU32(protocol::FieldId::Offset, (uint32_t)offset);

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        resp.setString(protocol::FieldId::Status, F("error"));
        resp.setString(protocol::FieldId::Message, F("File not found"));
        return;
    }

    size_t fileSize = f.size();
    resp.setString(protocol::FieldId::Status, F("ok"));
    resp.setU32(protocol::FieldId::Size, (uint32_t)fileSize);

    if (offset >= fileSize) {
        resp.setBytes(protocol::FieldId::Data, nullptr, 0);
        resp.setBool(protocol::FieldId::More, false);
        f.close();
        return;
    }

    f.seek(offset, SeekSet);

    size_t toRead = limit;
    if (offset + toRead > fileSize) {
        toRead = fileSize - offset;
    }

    uint8_t* buf = (uint8_t*)malloc(toRead);
    if (!buf) {
        resp.setString(protocol::FieldId::Status, F("error"));
        resp.setString(protocol::FieldId::Message, F("Out of memory"));
        f.close();
        return;
    }
    size_t n = f.read(buf, toRead);
    resp.setBytes(protocol::FieldId::Data, buf, n);
    free(buf);
    resp.setBool(protocol::FieldId::More, (offset + n < fileSize));

    f.close();
}

void FileBrowser::fileInfo(const String& path, protocol::CommandResponse& resp) {
    if (deny(resp, path)) return;
    resp.setString(protocol::FieldId::Path, path);

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        resp.setString(protocol::FieldId::Status, F("error"));
        resp.setString(protocol::FieldId::Message, F("Not found"));
        return;
    }

    resp.setString(protocol::FieldId::Status, F("ok"));
    resp.setString(protocol::FieldId::Type, f.isDirectory() ? F("dir") : F("file"));
    resp.setU32(protocol::FieldId::Size, (uint32_t)f.size());
    f.close();
}

void FileBrowser::deleteItem(const String& path, protocol::CommandResponse& resp) {
    if (deny(resp, path)) return;
    resp.setString(protocol::FieldId::Path, path);

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        resp.setString(protocol::FieldId::Status, F("error"));
        resp.setString(protocol::FieldId::Message, F("Not found"));
        return;
    }
    bool isDir = f.isDirectory();
    f.close();

    if (isDir) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Delete individual legacy files only");
        return;
    }
    bool ok = LITTLEFS.remove(path);

    if (ok) {
        resp.setString(protocol::FieldId::Status, F("ok"));
    } else {
        resp.setString(protocol::FieldId::Status, F("error"));
        resp.setString(protocol::FieldId::Message, F("Delete failed"));
    }
}

void FileBrowser::fsInfo(protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Status, F("ok"));
    resp.setU32(protocol::FieldId::TotalBytes, (uint32_t)compat::fsTotalBytes());
    resp.setU32(protocol::FieldId::UsedBytes, (uint32_t)compat::fsUsedBytes());
}
