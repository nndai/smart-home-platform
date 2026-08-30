#include "core/FileBrowser.h"
#include "compat/fs.h"
#include "core/Crypto.h"
#include <vector>
#include <algorithm>

void FileBrowser::listDir(const String& path, size_t offset, size_t limit, protocol::CommandResponse& resp) {
    File dir = LITTLEFS.open(path, "r");
    if (!dir) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Not a directory");
        resp.setString(protocol::FieldId::Path, path);
        return;
    }

    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Path, path);

    if (limit == 0) {
        auto entries = resp.beginArray(protocol::FieldId::Entries);
        compat::DirIterator it(path.c_str());
        String name;
        size_t size;
        bool isDir;
        while (it.next(name, size, isDir)) {
            auto e = entries->addBeginObject();
            e->setString(protocol::FieldId::Name, name);
            if (isDir) {
                e->setString(protocol::FieldId::Type, "dir");
            } else {
                e->setString(protocol::FieldId::Type, "file");
                e->setU32(protocol::FieldId::Size, (uint32_t)size);
            }
            entries->endObject(e);
        }
        it.close();
        resp.endArray(entries);
        return;
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
                e.type = "dir";
                e.size = 0;
            } else {
                e.type = "file";
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
    resp.setU32(protocol::FieldId::Total, (uint32_t)total);

    if (offset >= total) {
        auto entries = resp.beginArray(protocol::FieldId::Entries);
        resp.endArray(entries);
        resp.setBool(protocol::FieldId::More, false);
    } else {
        size_t end = offset + limit;
        if (end > total) end = total;
        resp.setBool(protocol::FieldId::More, (end < total));
        auto entries = resp.beginArray(protocol::FieldId::Entries);
        for (size_t i = offset; i < end; i++) {
            auto e = entries->addBeginObject();
            e->setString(protocol::FieldId::Name, all[i].name);
            e->setString(protocol::FieldId::Type, all[i].type);
            if (all[i].type == "file") {
                e->setU32(protocol::FieldId::Size, (uint32_t)all[i].size);
            }
            entries->endObject(e);
        }
        resp.endArray(entries);
    }
}

void FileBrowser::readFile(const String& path, size_t offset, size_t limit, bool encode, protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Path, path);
    resp.setBool(protocol::FieldId::Encode, encode);
    resp.setU32(protocol::FieldId::Offset, (uint32_t)offset);

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "File not found");
        return;
    }

    size_t fileSize = f.size();
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setU32(protocol::FieldId::Size, (uint32_t)fileSize);

    if (offset >= fileSize) {
        resp.setString(protocol::FieldId::Data, "");
        resp.setBool(protocol::FieldId::More, false);
        f.close();
        return;
    }

    f.seek(offset, SeekSet);

    size_t toRead = limit;
    if (offset + toRead > fileSize) {
        toRead = fileSize - offset;
    }

    if (encode) {
        uint8_t* buf = (uint8_t*)malloc(toRead);
        if (!buf) {
            resp.setString(protocol::FieldId::Status, "error");
            resp.setString(protocol::FieldId::Message, "Out of memory");
            f.close();
            return;
        }
        size_t n = f.read(buf, toRead);
        String encoded = crypto::base64Encode(buf, n);
        free(buf);
        resp.setString(protocol::FieldId::Data, encoded);
        resp.setBool(protocol::FieldId::More, (offset + n < fileSize));
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
        resp.setString(protocol::FieldId::Data, data);
        resp.setBool(protocol::FieldId::More, (offset + data.length() < fileSize));
    }

    f.close();
}

void FileBrowser::fileInfo(const String& path, protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Path, path);

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Not found");
        return;
    }

    resp.setString(protocol::FieldId::Status, "ok");
    resp.setString(protocol::FieldId::Type, f.isDirectory() ? "dir" : "file");
    resp.setU32(protocol::FieldId::Size, (uint32_t)f.size());
    f.close();
}

void FileBrowser::deleteItem(const String& path, protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Path, path);

    File f = LITTLEFS.open(path, "r");
    if (!f) {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Not found");
        return;
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
        resp.setString(protocol::FieldId::Status, "ok");
    } else {
        resp.setString(protocol::FieldId::Status, "error");
        resp.setString(protocol::FieldId::Message, "Delete failed");
    }
}

void FileBrowser::fsInfo(protocol::CommandResponse& resp) {
    resp.setString(protocol::FieldId::Status, "ok");
    resp.setU32(protocol::FieldId::TotalBytes, (uint32_t)compat::fsTotalBytes());
    resp.setU32(protocol::FieldId::UsedBytes, (uint32_t)compat::fsUsedBytes());
}
