#pragma once
#include <Arduino.h>
#include <FS.h>
#include <LittleFS.h>
#include "TimeManager.h"

class LogBase {
public:
    LogBase(TimeManager& tm, const char* dir) : _time(tm), _dir(dir) {}
    virtual ~LogBase() = default;
    virtual bool begin() = 0;

    size_t getSize()          { return _dirBytes(_dir); }
    bool   read(String& out, size_t maxBytes = 4096) { return _readDirConcat(_dir, out, maxBytes); }
    void   clear()            { _clearDir(_dir); }

protected:
    TimeManager& _time;
    const char* _dir;
    bool _mounted = false;

    static int     _listFiles(const char* dir, String* files, int maxCount);
    static void    _sortFilesByDate(String* files, int count);
    static size_t  _dirBytes(const char* dir);
    static void    _appendFile(const String& src, const String& dst, size_t maxSize);
    static void    _clearDir(const char* dir);
    static bool    _readDirConcat(const char* dir, String& out, size_t maxBytes);

    static String _oldestFile(const char* dir);
    static String _latestFile(const char* dir);

    static void    _rotateBySize(const char* dir, size_t maxFolder);
    static void    _migrateNosync(const char* dir, unsigned long epoch, size_t maxFileSize);
};
