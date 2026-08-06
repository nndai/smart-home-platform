#pragma once

#include <FS.h>
#include <Flash.h>
#include <memory>
#include <cstring>
#include <cstdio>
#include <FreeRTOS.h>
#include <semphr.h>

// User partition on LN882H: 0x1EC000, 80KB (0x14000)
#ifndef LFS_START_ADDR
#define LFS_START_ADDR 0x1EC000
#endif
#ifndef LFS_SIZE
#define LFS_SIZE 0x14000
#endif
#ifndef LFS_BLOCK_SIZE
#define LFS_BLOCK_SIZE 4096
#endif

#ifndef LFS_NAME_MAX
#define LFS_NAME_MAX 48
#endif

#include "lfs.h"

#include <lt_logger.h>

#ifdef LITTLEFS_DEBUG
#define LOG_LITTLEFS(...)  LT_IM(LITTLEFS, __VA_ARGS__)
#else
#define LOG_LITTLEFS(...)
#endif

using namespace fs;

class LittleFSImpl;
class LittleFSFileImpl;

class LittleFSImpl : public FSImpl {
public:
    LittleFSImpl(uint32_t startAddr, uint32_t size, uint32_t blockSize);
    ~LittleFSImpl();

    bool begin();
    void end();
    bool format();

    FileImplPtr open(const char* path, const char* mode, const bool create) override;
    bool exists(const char* path) override;
    bool rename(const char* pathFrom, const char* pathTo) override;
    bool remove(const char* path) override;
    bool mkdir(const char* path) override;
    bool rmdir(const char* path) override;

    size_t totalBytes() const { return _size; }
    size_t usedBytes();

protected:
    friend class LittleFSFileImpl;

    lfs_t* getFS() { return &_lfs; }
    bool _tryMount();

    static int _lfsRead(const lfs_config* c, lfs_block_t block, lfs_off_t off, void* buffer, lfs_size_t size);
    static int _lfsProg(const lfs_config* c, lfs_block_t block, lfs_off_t off, const void* buffer, lfs_size_t size);
    static int _lfsErase(const lfs_config* c, lfs_block_t block);
    static int _lfsSync(const lfs_config* c);

    static int _lock(const lfs_config* c);
    static int _unlock(const lfs_config* c);

    static int _parseMode(const char* mode, bool create);
    static bool _pathValid(const char* path);

    lfs_t _lfs;
    lfs_config _lfsCfg;

    uint32_t _startAddr;
    uint32_t _size;
    uint32_t _blockSize;
    bool _mounted;
    SemaphoreHandle_t _mutex;
};

class LittleFSFileImpl : public FileImpl {
public:
    LittleFSFileImpl(LittleFSImpl* fs, const char* path, std::shared_ptr<lfs_file_t> fd, bool isDir);
    ~LittleFSFileImpl();

    size_t write(const uint8_t* buf, size_t size) override;
    size_t read(uint8_t* buf, size_t size) override;
    void flush() override;
    bool seek(uint32_t pos, SeekMode mode) override;
    size_t position() const override;
    size_t size() const override;
    bool setBufferSize(size_t size) override;
    void close() override;
    time_t getLastWrite() override;
    const char* path() const override;
    const char* name() const override;
    boolean isDirectory() override;
    FileImplPtr openNextFile(const char* mode) override;
    void rewindDirectory() override;
    operator bool() override;

protected:
    lfs_file_t* _getFD() const { return _fd.get(); }

    LittleFSImpl* _fs;
    std::shared_ptr<lfs_file_t> _fd;
    std::shared_ptr<char> _path;
    bool _isDir;
    bool _opened;

    lfs_dir_t* _dir;
    bool _dirOpened;
};

class LittleFS : public FS {
public:
    LittleFS() : FS(FSImplPtr(new LittleFSImpl(LFS_START_ADDR, LFS_SIZE, LFS_BLOCK_SIZE))) {}

    bool begin() { return static_cast<LittleFSImpl*>(_impl.get())->begin(); }
    void end() { static_cast<LittleFSImpl*>(_impl.get())->end(); }
    bool format() { return static_cast<LittleFSImpl*>(_impl.get())->format(); }
    size_t totalBytes() { return static_cast<LittleFSImpl*>(_impl.get())->totalBytes(); }
    size_t usedBytes() { return static_cast<LittleFSImpl*>(_impl.get())->usedBytes(); }
};

#ifndef NO_GLOBAL_LITTLEFS
extern LittleFS LITTLEFS;
#endif
