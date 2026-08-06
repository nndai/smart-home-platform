#include "LittleFS.h"


LittleFS LITTLEFS;

// ── LittleFSImpl ────────────────────────────────────────────

LittleFSImpl::LittleFSImpl(uint32_t startAddr, uint32_t size, uint32_t blockSize)
    : _startAddr(startAddr)
    , _size(size)
    , _blockSize(blockSize)
    , _mounted(false)
    , _mutex(nullptr)
{
    memset(&_lfs, 0, sizeof(_lfs));
    memset(&_lfsCfg, 0, sizeof(_lfsCfg));

    _mutex = xSemaphoreCreateRecursiveMutex();

    if (_size && _blockSize) {
        _lfsCfg.context = this;
        _lfsCfg.read = _lfsRead;
        _lfsCfg.prog = _lfsProg;
        _lfsCfg.erase = _lfsErase;
        _lfsCfg.sync = _lfsSync;
        _lfsCfg.lock = _lock;
        _lfsCfg.unlock = _unlock;
        _lfsCfg.read_size = 64;
        _lfsCfg.prog_size = 64;
        _lfsCfg.block_size = _blockSize;
        _lfsCfg.block_count = _size / _blockSize;
#ifndef LFS_BLOCK_CYCLES
        _lfsCfg.block_cycles = 64;
#else
        _lfsCfg.block_cycles = LFS_BLOCK_CYCLES;
#endif
        _lfsCfg.cache_size = 256;
        _lfsCfg.lookahead_size = 64;
        _lfsCfg.name_max = LFS_NAME_MAX;
    }
}

LittleFSImpl::~LittleFSImpl() {
    end();
}

bool LittleFSImpl::begin() {
    if (_mounted) return true;
    if (_blockSize == 0 || _size == 0) return false;
    if (_tryMount()) return true;
    if (format()) return _tryMount();
    return false;
}

void LittleFSImpl::end() {
    if (_mounted) {
        lfs_unmount(&_lfs);
        _mounted = false;
    }
}

bool LittleFSImpl::format() {
    bool wasMounted = _mounted;
    if (_mounted) {
        lfs_unmount(&_lfs);
        _mounted = false;
    }
    memset(&_lfs, 0, sizeof(_lfs));
    int rc = lfs_format(&_lfs, &_lfsCfg);
    if (rc != 0) return false;
    if (wasMounted) return _tryMount();
    return true;
}

bool LittleFSImpl::_tryMount() {
    if (_mounted) {
        lfs_unmount(&_lfs);
        _mounted = false;
    }
    memset(&_lfs, 0, sizeof(_lfs));
    int rc = lfs_mount(&_lfs, &_lfsCfg);
    if (rc == 0) _mounted = true;
    return _mounted;
}

size_t LittleFSImpl::usedBytes() {
    if (!_mounted) return 0;
    return lfs_fs_size(&_lfs) * _blockSize;
}

bool LittleFSImpl::_pathValid(const char *path) {
    while (*path) {
        const char *slash = strchr(path, '/');
        if (!slash) {
            if (strlen(path) >= LFS_NAME_MAX) return false;
            break;
        }
        if ((slash - path) >= LFS_NAME_MAX) return false;
        path = slash + 1;
    }
    return true;
}

FileImplPtr LittleFSImpl::open(const char* path, const char* mode, const bool create) {
    if (!_mounted || !path || !path[0]) return FileImplPtr();
    if (!_pathValid(path)) return FileImplPtr();

    int flags = _parseMode(mode, create);
    if (flags < 0) return FileImplPtr();

    auto fd = std::make_shared<lfs_file_t>();

    if ((flags & LFS_O_CREAT) && strchr(path, '/')) {
        char* pathStr = strdup(path);
        if (pathStr) {
            char* ptr = strchr(pathStr, '/');
            while (ptr) {
                *ptr = 0;
                lfs_mkdir(&_lfs, pathStr);
                *ptr = '/';
                ptr = strchr(ptr + 1, '/');
            }
            free(pathStr);
        }
    }

    int rc = lfs_file_open(&_lfs, fd.get(), path, flags);
    if (rc == LFS_ERR_ISDIR) {
        auto impl = std::make_shared<LittleFSFileImpl>(this, path, nullptr, true);
        uint32_t vtable = *(uint32_t*)impl.get();
        LOG_LITTLEFS( "open(DIR) vtable=0x%08x", vtable);
        return impl;
    } else if (rc == 0) {
        auto impl = std::make_shared<LittleFSFileImpl>(this, path, fd, false);
        uint32_t vtable = *(uint32_t*)impl.get();
        LOG_LITTLEFS( "open(FILE) vtable=0x%08x", vtable);
        return impl;
    }
    return FileImplPtr();
}

bool LittleFSImpl::exists(const char* path) {
    if (!_mounted || !path || !path[0]) return false;
    lfs_info info;
    return lfs_stat(&_lfs, path, &info) == 0;
}

bool LittleFSImpl::rename(const char* pathFrom, const char* pathTo) {
    if (!_mounted || !pathFrom || !pathFrom[0] || !pathTo || !pathTo[0]) return false;
    return lfs_rename(&_lfs, pathFrom, pathTo) == 0;
}

bool LittleFSImpl::remove(const char* path) {
    if (!_mounted || !path || !path[0]) return false;
    int rc = lfs_remove(&_lfs, path);
    if (rc != 0) return false;
    char* pathStr = strdup(path);
    if (pathStr) {
        char* ptr = strrchr(pathStr, '/');
        while (ptr && ptr > pathStr) {
            *ptr = 0;
            lfs_remove(&_lfs, pathStr);
            ptr = strrchr(pathStr, '/');
        }
        free(pathStr);
    }
    return true;
}

bool LittleFSImpl::mkdir(const char* path) {
    if (!_mounted || !path || !path[0]) return false;
    return lfs_mkdir(&_lfs, path) == 0;
}

bool LittleFSImpl::rmdir(const char* path) {
    return remove(path);
}

int LittleFSImpl::_parseMode(const char* mode, bool create) {
    int flags = 0;
    if (strcmp(mode, "r") == 0) {
        flags = LFS_O_RDONLY;
    } else if (strcmp(mode, "w") == 0) {
        flags = LFS_O_WRONLY | LFS_O_CREAT | LFS_O_TRUNC;
    } else if (strcmp(mode, "a") == 0) {
        flags = LFS_O_WRONLY | LFS_O_CREAT | LFS_O_APPEND;
    } else {
        return -1;
    }
    if (create && (flags & LFS_O_RDONLY)) {
        flags = (flags & ~LFS_O_RDONLY) | LFS_O_RDWR | LFS_O_CREAT;
    }
    return flags;
}

int LittleFSImpl::_lfsRead(const lfs_config* c, lfs_block_t block, lfs_off_t off, void* buffer, lfs_size_t size) {
    auto* me = reinterpret_cast<LittleFSImpl*>(c->context);
    uint32_t addr = me->_startAddr + (block * me->_blockSize) + off;
    if (!Flash.readBlock(addr, reinterpret_cast<uint8_t*>(buffer), size)) return -1;
    return 0;
}

int LittleFSImpl::_lfsProg(const lfs_config* c, lfs_block_t block, lfs_off_t off, const void* buffer, lfs_size_t size) {
    auto* me = reinterpret_cast<LittleFSImpl*>(c->context);
    uint32_t addr = me->_startAddr + (block * me->_blockSize) + off;
    if (!Flash.writeBlock(addr, reinterpret_cast<const uint8_t*>(buffer), size)) return -1;
    return 0;
}

int LittleFSImpl::_lfsErase(const lfs_config* c, lfs_block_t block) {
    auto* me = reinterpret_cast<LittleFSImpl*>(c->context);
    uint32_t addr = me->_startAddr + (block * me->_blockSize);
    if (!Flash.eraseSector(addr)) return -1;
    return 0;
}

int LittleFSImpl::_lock(const lfs_config* c) {
    auto* me = reinterpret_cast<LittleFSImpl*>(c->context);
    if (!me->_mutex) return LFS_ERR_IO;
    return xSemaphoreTakeRecursive(me->_mutex, portMAX_DELAY) == pdTRUE ? 0 : LFS_ERR_IO;
}

int LittleFSImpl::_unlock(const lfs_config* c) {
    auto* me = reinterpret_cast<LittleFSImpl*>(c->context);
    if (!me->_mutex) return LFS_ERR_IO;
    return xSemaphoreGiveRecursive(me->_mutex) == pdTRUE ? 0 : LFS_ERR_IO;
}

int LittleFSImpl::_lfsSync(const lfs_config* c) {
    (void)c;
    return 0;
}

// ── LittleFSFileImpl ────────────────────────────────────────

LittleFSFileImpl::LittleFSFileImpl(LittleFSImpl* fs, const char* path, std::shared_ptr<lfs_file_t> fd, bool isDir)
    : _fs(fs)
    , _fd(fd)
    , _isDir(isDir)
    , _opened(true)
    , _dir(nullptr)
    , _dirOpened(false)
{
    size_t len = strlen(path) + 1;
    _path = std::shared_ptr<char>(new char[len], std::default_delete<char[]>());
    memcpy(_path.get(), path, len);
    LOG_LITTLEFS( "ctor: path='%s' isDir=%d fd=%p this=%p", path, isDir, (void*)_fd.get(), (void*)this);
}

LittleFSFileImpl::~LittleFSFileImpl() {
    LOG_LITTLEFS( "dtor: path='%s' opened=%d this=%p", _path ? _path.get() : "(null)", _opened, (void*)this);
    if (_opened) close();
}

size_t LittleFSFileImpl::write(const uint8_t* buf, size_t size) {
    if (!_opened || !_fd || !buf) return 0;
    int result = lfs_file_write(_fs->getFS(), _getFD(), buf, size);
    if (result < 0) {
        LOG_LITTLEFS("write failed: %d", result);
        return 0;
    }
    return result;
}

size_t LittleFSFileImpl::read(uint8_t* buf, size_t size) {
    if (!_opened || !_fd || !buf) return 0;
    int result = lfs_file_read(_fs->getFS(), _getFD(), buf, size);
    return result < 0 ? 0 : result;
}

void LittleFSFileImpl::flush() {
    if (!_opened || !_fd) return;
    lfs_file_sync(_fs->getFS(), _getFD());
}

bool LittleFSFileImpl::seek(uint32_t pos, SeekMode mode) {
    if (!_opened || !_fd) return false;
    int32_t offset = static_cast<int32_t>(pos);
    if (mode == SeekEnd) offset = -offset;
    int rc = lfs_file_seek(_fs->getFS(), _getFD(), offset, static_cast<int>(mode));
    return rc >= 0;
}

size_t LittleFSFileImpl::position() const {
    if (!_opened || !_fd) return 0;
    int result = lfs_file_tell(_fs->getFS(), _getFD());
    return result < 0 ? 0 : result;
}

size_t LittleFSFileImpl::size() const {
    if (!_opened || !_fd) return 0;
    int result = lfs_file_size(_fs->getFS(), _getFD());
    return result < 0 ? 0 : result;
}

bool LittleFSFileImpl::setBufferSize(size_t size) {
    (void)size;
    return false;
}

void LittleFSFileImpl::close() {
    if (_opened) {
        LOG_LITTLEFS( "close: path='%s' fd=%p dir=%p", _path.get(), (void*)_fd.get(), (void*)_dir);
        if (_fd) {
            lfs_file_close(_fs->getFS(), _getFD());
        }
        if (_dir && _dirOpened) {
            lfs_dir_close(_fs->getFS(), _dir);
            delete _dir;
            _dir = nullptr;
            _dirOpened = false;
        }
        _opened = false;
    }
}

time_t LittleFSFileImpl::getLastWrite() {
    time_t t = 0;
    if (_opened) {
        lfs_getattr(_fs->getFS(), _path.get(), 't', &t, sizeof(t));
    }
    return t;
}

const char* LittleFSFileImpl::path() const {
    return _opened ? _path.get() : nullptr;
}

const char* LittleFSFileImpl::name() const {
    LOG_LITTLEFS( "name: opened=%d path='%s'", _opened, _path.get());
    if (!_opened) return nullptr;
    const char* p = _path.get();
    const char* slash = strrchr(p, '/');
    const char* result = (slash && slash[1]) ? slash + 1 : p;
    LOG_LITTLEFS( "name result='%s'", result);
    return result;
}

boolean LittleFSFileImpl::isDirectory() {
    LOG_LITTLEFS( "isDirectory: path='%s' -> %d", _path.get(), _isDir);
    return _isDir;
}

FileImplPtr LittleFSFileImpl::openNextFile(const char* mode) {
    LOG_LITTLEFS( "openNextFile: path='%s' isDir=%d opened=%d dir=%p",
          _path.get(), _isDir, _opened, (void*)_dir);
    if (!_isDir) return FileImplPtr();

    if (!_dir) {
        _dir = new lfs_dir_t;
        int rc = lfs_dir_open(_fs->getFS(), _dir, _path.get());
        LOG_LITTLEFS( "lfs_dir_open(%s) = %d", _path.get(), rc);
        if (rc != 0) {
            delete _dir;
            _dir = nullptr;
            return FileImplPtr();
        }
        _dirOpened = true;
        lfs_info skip;
        lfs_dir_read(_fs->getFS(), _dir, &skip);
        lfs_dir_read(_fs->getFS(), _dir, &skip);
        LOG_LITTLEFS( "skip . .. done");
    }

    lfs_info info;
    int rc = lfs_dir_read(_fs->getFS(), _dir, &info);
    LOG_LITTLEFS( "lfs_dir_read rc=%d name='%s' type=%d", rc, info.name, info.type);
    if (rc <= 0) return FileImplPtr();

    char fullPath[LFS_NAME_MAX + 64];
    const char* base = _path.get();
    if (strcmp(base, "/") == 0) {
        snprintf(fullPath, sizeof(fullPath), "/%s", info.name);
        LOG_LITTLEFS( "ROOT path: fullPath='%s'", fullPath);
    } else {
        snprintf(fullPath, sizeof(fullPath), "%s/%s", base, info.name);
        LOG_LITTLEFS( "SUB path: fullPath='%s'", fullPath);
    }

    if (info.type == LFS_TYPE_DIR) {
        LOG_LITTLEFS( "entry is DIR, before new");
        auto* raw = new LittleFSFileImpl(_fs, fullPath, nullptr, true);
        uint32_t vtable = *(uint32_t*)raw;
        LOG_LITTLEFS( "entry is DIR, after new raw=%p vtable=0x%08x", (void*)raw, vtable);
        FileImplPtr impl(raw);
        vtable = *(uint32_t*)impl.get();
        LOG_LITTLEFS( "entry is DIR, after wrap impl=%p vtable=0x%08x refs=%ld",
              (void*)impl.get(), vtable, impl.use_count());
        return impl;
    }

    int flags = LittleFSImpl::_parseMode(mode, false);
    if (flags < 0) flags = LFS_O_RDONLY;

    auto fd = std::make_shared<lfs_file_t>();
    rc = lfs_file_open(_fs->getFS(), fd.get(), fullPath, flags);
    LOG_LITTLEFS( "lfs_file_open(%s) = %d", fullPath, rc);
    if (rc == 0) {
        return std::make_shared<LittleFSFileImpl>(_fs, fullPath, fd, false);
    }
    return FileImplPtr();
}

void LittleFSFileImpl::rewindDirectory() {
    LOG_LITTLEFS( "rewindDirectory: path='%s' isDir=%d dir=%p dirOpened=%d",
          _path.get(), _isDir, (void*)_dir, _dirOpened);
    if (_isDir && _dir && _dirOpened) {
        lfs_dir_rewind(_fs->getFS(), _dir);
        lfs_info skip;
        lfs_dir_read(_fs->getFS(), _dir, &skip);
        lfs_dir_read(_fs->getFS(), _dir, &skip);
        LOG_LITTLEFS( "rewindDirectory done");
    }
}

LittleFSFileImpl::operator bool() {
    LOG_LITTLEFS( "bool: path='%s' opened=%d", _path.get(), _opened);
    return _opened;
}
