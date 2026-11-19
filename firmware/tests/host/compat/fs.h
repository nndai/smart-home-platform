#pragma once
#include <Arduino.h>
#include <filesystem>
#include <fstream>
#include <memory>
#include <vector>
inline std::filesystem::path hostRoot;
inline uint32_t hostScanEntries = 0, hostOpens = 0;
inline int hostPartialWrite = -1;
inline uint32_t hostCapacity = 128 * 1024;
constexpr int SeekSet = 0;
class File {
    struct State { std::fstream file; std::filesystem::path path; bool append; };
    std::shared_ptr<State> state;
public:
    File() = default;
    File(const std::filesystem::path& path, const char* mode) {
        state = std::make_shared<State>(); state->path = path; state->append = *mode == 'a';
        auto flags = std::ios::binary | (*mode == 'r' ? std::ios::in : std::ios::out);
        if (*mode == 'w') flags |= std::ios::trunc;
        if (*mode == 'a') flags |= std::ios::app;
        state->file.open(path, flags);
    }
    explicit operator bool() const { return state && state->file.is_open(); }
    size_t size() { return *this ? std::filesystem::file_size(state->path) : 0; }
    bool seek(size_t offset, int) { if (!*this) return false; state->file.clear(); state->file.seekg(offset); return bool(state->file); }
    size_t read(uint8_t* bytes, size_t n) {
        if (!*this) return 0;
        state->file.read(reinterpret_cast<char*>(bytes), n); return state->file.gcount();
    }
    size_t write(const uint8_t* bytes, size_t n) {
        if (!*this) return 0;
        if (hostPartialWrite >= 0 && state->path.extension() == ".seg" && state->append) {
            n = std::min(n, size_t(hostPartialWrite)); hostPartialWrite = -1;
        }
        state->file.write(reinterpret_cast<const char*>(bytes), n); state->file.flush();
        return state->file ? n : 0;
    }
    void flush() { if (*this) state->file.flush(); }
    void close() { if (*this) state->file.close(); state.reset(); }
};
struct HostFS {
    File open(const String& path, const char* mode) {
        ++hostOpens;
        return File(hostRoot / path.substr(1), mode);
    }
    bool mkdir(const char* path) { std::error_code ec; std::filesystem::create_directories(hostRoot / (path+1), ec); return !ec; }
    bool remove(const String& path) { std::error_code ec; return std::filesystem::remove(hostRoot / path.substr(1), ec); }
};
inline HostFS LITTLEFS;
namespace compat {
inline size_t fsTotalBytes() { return hostCapacity; }
inline size_t fsUsedBytes() {
    size_t total = 0;
    for (auto& e : std::filesystem::recursive_directory_iterator(hostRoot)) if (e.is_regular_file()) total += e.file_size();
    return total;
}
class DirIterator {
    std::vector<std::filesystem::path> entries;
    size_t index = 0;
public:
    DirIterator(const char* path) {
        auto dir = hostRoot / (path+1);
        if (std::filesystem::exists(dir)) for (auto& e : std::filesystem::directory_iterator(dir)) entries.push_back(e.path());
    }
    bool next(String& name, size_t& size, bool& dir) {
        if (index == entries.size()) return false;
        auto path = entries[index++];
        if (!std::filesystem::exists(path)) return next(name, size, dir);
        ++hostScanEntries; name = path.filename().string(); dir = std::filesystem::is_directory(path);
        size = dir ? 0 : std::filesystem::file_size(path); return true;
    }
    void close() {}
};
}
