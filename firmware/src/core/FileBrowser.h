#pragma once
#include <Arduino.h>
#include "compat/log.h"
#include "protocol/CommandContext.h"

class FileBrowser {
public:
    static void listDir(const String& path, size_t offset, size_t limit, protocol::CommandResponse& resp);
    static void readFile(const String& path, size_t offset, size_t limit, protocol::CommandResponse& resp);
    static void fileInfo(const String& path, protocol::CommandResponse& resp);
    static void deleteItem(const String& path, protocol::CommandResponse& resp);
    static void fsInfo(protocol::CommandResponse& resp);
};
