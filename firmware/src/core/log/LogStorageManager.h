#pragma once
#include "LogRecordCodec.h"
#include "compat/fs.h"
#include "compat/storage_lock.h"
#include "compat/log_storage_config.h"
#include "protocol/CommandContext.h"
#include "protocol/BinaryCommandIds.h"
#include <atomic>

// One owner for v2 files. Producers only enqueue bounded records.
class LogStorageManager {
public:
    static LogStorageManager& instance();
    bool begin();
    bool enqueue(logv2::Stream stream, const char* text, uint32_t epoch = 0,
                 logv2::RecordType type = logv2::Data);
    void handle(protocol::CommandId command, const protocol::CommandRequest& request,
                protocol::CommandResponse& response);
    size_t size(logv2::Stream stream);
    bool clear(logv2::Stream stream);
    static constexpr uint8_t MAX_SEGMENTS = LOG_V2_MAX_SEGMENTS;
    static constexpr uint16_t CHUNK_SIZE = 768;

private:
    struct Entry {
        uint32_t id = 0, size = 0, minEpoch = 0, maxEpoch = 0, month = 0;
        bool sealed = true;
    };
    struct Store {
        Entry entries[MAX_SEGMENTS];
        uint32_t generation = 1, revision = 0, catalogRevision = 0;
        uint32_t checkpoint = 0, nextId = 1;
        std::atomic<uint32_t> bytes{0};
        std::atomic<uint32_t> dropped{0};
        uint32_t quota = 0, segmentLimit = 0;
        uint8_t count = 0;
        bool dirty = false, needsSpace = false, hasIdentity = false;
        const char* state = "recovering";
    };
    struct Pending {
        char* text;
        uint32_t epoch, uptime, generation;
        uint8_t stream, type;
    };
    Store _stores[logv2::StreamCount];
    std::atomic<uint32_t> _enqueueGeneration[logv2::StreamCount];
    compat::StorageLock _lock;
    QueueHandle_t _important = nullptr, _diagnostic = nullptr;
    compat::DirIterator* _iterator = nullptr;
    File _recoveryFile;
    uint8_t _recoverStream = 0, _recoverEntry = 0;
    bool _scanned = false, _ready = false, _started = false, _pressure = false;
    uint8_t _orphanStreamsRemaining = logv2::StreamCount;
    uint32_t _boot = 1, _lastCheckpoint = 0, _lastSpaceCheck = 0, _retryAt = 0;
    uint32_t _free = 0, _low = 16384, _target = 24576;
    uint32_t _scanEntries = 0, _readChunks = 0;
    std::atomic<uint32_t> _queueHighWater{0};
    std::atomic<uint32_t> _clockSequence{0}, _clockMillis{0}, _clockSeconds{0}, _clockRemainder{0};
    uint8_t _buffer[CHUNK_SIZE];
    static uint32_t tick();
    void work();
    void sampleClock();
    uint32_t uptimeSeconds() const;
    void recoverStep();
    bool checkpoint(uint8_t stream);
    void loadCheckpoint(uint8_t stream);
    bool createSegment(uint8_t stream, uint32_t month);
    bool writeSegmentHeader(uint8_t stream, uint32_t generation, uint32_t id);
    bool removeEntry(uint8_t stream, uint8_t index);
    bool append(const Pending& pending);
    bool cleanupStep();
    void state(protocol::CommandResponse& response, uint8_t stream);
    void sync(const protocol::CommandRequest& request, protocol::CommandResponse& response, uint8_t stream);
    void list(const protocol::CommandRequest& request, protocol::CommandResponse& response, uint8_t stream);
    String path(uint8_t stream, uint32_t id) const;
    static const char* directory(uint8_t stream);
};
