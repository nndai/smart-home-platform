#include "LogStorageManager.h"
#include <Config.h>
#include <algorithm>
#include <limits.h>
#include <string.h>
#include <time.h>

using namespace logv2;
using protocol::FieldId;

namespace {
constexpr size_t CHECKPOINT_HEADER = 36, ENTRY_BYTES = 24;
constexpr size_t CHECKPOINT_SIZE = CHECKPOINT_HEADER + LogStorageManager::MAX_SEGMENTS * ENTRY_BYTES + 4;
static_assert(CHECKPOINT_SIZE <= LogStorageManager::CHUNK_SIZE, "Checkpoint must fit the shared buffer");
void status(protocol::CommandResponse& r, const char* value) { r.setString(FieldId::Status, value); }
uint32_t monthOf(uint32_t epoch) {
    if (!epoch) return 0;
    time_t value = epoch;
    tm t;
    gmtime_r(&value, &t);
    return (t.tm_year + 1900) * 12 + t.tm_mon + 1;
}
}

LogStorageManager& LogStorageManager::instance() { static LogStorageManager value; return value; }
const char* LogStorageManager::directory(uint8_t s) {
    static const char* paths[] = {"/logs/v2/power", "/logs/v2/toggle", "/logs/v2/sys"};
    return paths[s];
}
String LogStorageManager::path(uint8_t stream, uint32_t id) const {
    char name[24];
    snprintf(name, sizeof(name), "/%012lu.seg", (unsigned long)id);
    return String(directory(stream)) + name;
}

bool LogStorageManager::writeSegmentHeader(uint8_t stream, uint32_t generation, uint32_t id) {
    encodeHeader(_buffer, Stream(stream), generation, id);
    File f = LITTLEFS.open(path(stream, id), "w");
    bool ok = f && f.write(_buffer, HEADER_SIZE) == HEADER_SIZE;
    if (f) { f.flush(); f.close(); }
    uint32_t actualGeneration = 0, actualId = 0;
    f = LITTLEFS.open(path(stream, id), "r");
    ok = ok && f && f.size() == HEADER_SIZE && f.read(_buffer, HEADER_SIZE) == HEADER_SIZE &&
        decodeHeader(_buffer, Stream(stream), actualGeneration, actualId) && actualGeneration == generation && actualId == id;
    f.close();
    return ok;
}

bool LogStorageManager::begin() {
    if (_started) return true;
    if (!_lock.begin()) return false;
    const uint32_t startedAt = millis();
    _clockMillis = startedAt; _clockSeconds = startedAt / 1000; _clockRemainder = startedAt % 1000;
    LITTLEFS.mkdir("/logs"); LITTLEFS.mkdir("/logs/v2");
    const uint32_t total = compat::fsTotalBytes();
    _low = std::max(uint32_t(LOG_V2_FREE_MIN), total / 20);
    _target = _low + std::max(uint32_t(8192), total / 40);
    _free = total > compat::fsUsedBytes() ? total - compat::fsUsedBytes() : 0;
    // Quotas share the real partition, including room for config and filesystem metadata.
    const uint32_t budget = total > _target + 16384 ? total - _target - 16384 : 0;
    for (uint8_t s = 0; s < StreamCount; ++s) {
        LITTLEFS.mkdir(directory(s));
        auto& store = _stores[s];
        store.quota = budget * (s == Power ? 55 : s == Toggle ? 25 : 20) / 100;
        store.segmentLimit = std::max(uint32_t(1024), store.quota / 8);
        store.segmentLimit = std::min(store.segmentLimit, uint32_t(LOG_V2_SEGMENT_MAX));
        loadCheckpoint(s);
        if (!store.hasIdentity) store.generation = compat::newStorageIdentity();
        _enqueueGeneration[s] = store.generation;
    }
    _important = xQueueCreate(LOG_V2_IMPORTANT_QUEUE, sizeof(Pending));
    _diagnostic = xQueueCreate(LOG_V2_SYS_QUEUE, sizeof(Pending));
    if (!_important || !_diagnostic) return false;
    _started = sysTaskCreate("logStorage", LOG_V2_TICK_MS, tick, TASK_LOGWRITER_STACK, TASK_LOGWRITER_PRIO) != nullptr;
    return _started;
}

void LogStorageManager::loadCheckpoint(uint8_t stream) {
    auto& s = _stores[stream];
    for (const char* slot : {"/catalog.a", "/catalog.b"}) {
        File f = LITTLEFS.open(String(directory(stream)) + slot, "r");
        const size_t n = f ? f.read(_buffer, CHECKPOINT_SIZE) : 0;
        f.close();
        if (n != CHECKPOINT_SIZE || memcmp(_buffer, "SHC2", 4) ||
            _buffer[4] != SCHEMA || _buffer[5] != stream ||
            get32(_buffer + CHECKPOINT_SIZE - 4) != checksum(_buffer, CHECKPOINT_SIZE - 4)) continue;
        const uint32_t seq = get32(_buffer + 8);
        if (s.checkpoint && int32_t(seq - s.checkpoint) <= 0) continue;
        if (!get32(_buffer + 12) || !get32(_buffer + 16) || _buffer[6] > MAX_SEGMENTS) continue;
        s.checkpoint = seq;
        s.generation = get32(_buffer + 12);
        s.hasIdentity = true;
        s.nextId = get32(_buffer + 16);
        s.dropped = get32(_buffer + 24);
        _boot = std::max(_boot, get32(_buffer + 20) + 1);
        // Rebuild entries incrementally from disk; checkpoint identity survives clear/reboot.
    }
}

bool LogStorageManager::checkpoint(uint8_t stream) {
    auto& s = _stores[stream];
    memset(_buffer, 0, CHECKPOINT_SIZE);
    memcpy(_buffer, "SHC2", 4); _buffer[4] = SCHEMA; _buffer[5] = stream; _buffer[6] = s.count;
    put32(_buffer + 8, s.checkpoint + 1);
    put32(_buffer + 12, s.generation); put32(_buffer + 16, s.nextId);
    put32(_buffer + 20, _boot); put32(_buffer + 24, s.dropped);
    for (uint8_t i = 0; i < s.count; ++i) {
        const auto& e = s.entries[i];
        uint8_t* p = _buffer + CHECKPOINT_HEADER + i * ENTRY_BYTES;
        put32(p, e.id); put32(p + 4, e.size); put32(p + 8, e.minEpoch);
        put32(p + 12, e.maxEpoch); put32(p + 16, e.month); put32(p + 20, e.sealed);
    }
    put32(_buffer + CHECKPOINT_SIZE - 4, checksum(_buffer, CHECKPOINT_SIZE - 4));
    const String file = String(directory(stream)) + ((s.checkpoint + 1) & 1 ? "/catalog.a" : "/catalog.b");
    File f = LITTLEFS.open(file, "w");
    bool ok = f && f.write(_buffer, CHECKPOINT_SIZE) == CHECKPOINT_SIZE;
    if (f) { f.flush(); f.close(); }
    // Arduino flush() has no result: read back the checkpoint before publishing its identity.
    f = LITTLEFS.open(file, "r");
    ok = ok && f && f.size() == CHECKPOINT_SIZE && f.read(_buffer, CHECKPOINT_SIZE) == CHECKPOINT_SIZE &&
        get32(_buffer + CHECKPOINT_SIZE - 4) == checksum(_buffer, CHECKPOINT_SIZE - 4);
    f.close();
    if (ok) { ++s.checkpoint; s.dirty = false; }
    else s.state = "io_error";
    return ok;
}

uint32_t LogStorageManager::tick() { instance().work(); return TASK_KEEP_INTERVAL; }

void LogStorageManager::recoverStep() {
    if (_recoverStream >= StreamCount) {
        // Persist the new boot token before accepting any writes.
        for (uint8_t s = 0; s < StreamCount; ++s) if (!checkpoint(s)) {
            _retryAt = millis() + 5000;
            return;
        }
        _ready = true;
        for (uint8_t i = 0; i < StreamCount; ++i) {
            _stores[i].state = "ready";
            _enqueueGeneration[i] = _stores[i].generation;
        }
        return;
    }
    auto& s = _stores[_recoverStream];
    if (!_scanned) {
        if (!_iterator) _iterator = new compat::DirIterator(directory(_recoverStream));
        String name; size_t size; bool dir;
        if (_iterator->next(name, size, dir)) {
            ++_scanEntries;
            name = name.substring(name.lastIndexOf('/') + 1);
            if (dir || !name.endsWith(".seg") || name.length() != 16) return;
            uint32_t fileId = strtoul(name.c_str(), nullptr, 10);
            // Even a damaged header must not allow the filename's ID to be reused.
            if (fileId >= s.nextId) s.nextId = fileId == UINT32_MAX ? UINT32_MAX : fileId + 1;
            File f = LITTLEFS.open(String(directory(_recoverStream)) + "/" + name, "r");
            uint32_t gen = 0, id = 0;
            bool valid = f && f.read(_buffer, HEADER_SIZE) == HEADER_SIZE &&
                decodeHeader(_buffer, Stream(_recoverStream), gen, id) && id == fileId;
            f.close();
            if (!valid || (s.hasIdentity && gen < s.generation)) return;
            if (!s.hasIdentity || gen > s.generation) { s.generation = gen; s.count = 0; }
            s.hasIdentity = true;
            if (s.count == MAX_SEGMENTS) {
                auto first = std::min_element(s.entries, s.entries + s.count,
                    [](const Entry& a, const Entry& b) { return a.id < b.id; });
                if (id <= first->id) return;
                *first = s.entries[--s.count];
            }
            Entry e; e.id = id; s.entries[s.count++] = e;
            return;
        }
        _iterator->close(); delete _iterator; _iterator = nullptr;
        std::sort(s.entries, s.entries + s.count, [](const Entry& a, const Entry& b) { return a.id < b.id; });
        _scanned = true;
        return;
    }
    if (_recoverEntry >= s.count) {
        ++_recoverStream; _recoverEntry = 0; _scanned = false;
        return;
    }
    auto& e = s.entries[_recoverEntry];
    if (!_recoveryFile) {
        _recoveryFile = LITTLEFS.open(path(_recoverStream, e.id), "r");
        if (!_recoveryFile || !_recoveryFile.seek(HEADER_SIZE, SeekSet)) {
            _recoveryFile.close(); s.state = "io_error"; _retryAt = millis() + 5000; return;
        }
        e.size = HEADER_SIZE;
        s.bytes += HEADER_SIZE;
    }
    // Recover one record per tick. A corrupt tail is sealed at the last valid byte.
    const size_t n = _recoveryFile.read(_buffer, RECORD_HEADER_SIZE);
    const size_t full = n == RECORD_HEADER_SIZE ? recordSize(_buffer) : 0;
    if (!full || _recoveryFile.read(_buffer + RECORD_HEADER_SIZE, full - RECORD_HEADER_SIZE) != full - RECORD_HEADER_SIZE ||
        !validRecord(_buffer, full)) {
        _recoveryFile.close(); ++_recoverEntry; return;
    }
    e.size += full; s.bytes += full;
    const uint32_t epoch = get32(_buffer + 16);
    _boot = std::max(_boot, get32(_buffer + 8) + 1);
    if (epoch) {
        e.minEpoch = e.minEpoch ? std::min(e.minEpoch, epoch) : epoch;
        e.maxEpoch = std::max(e.maxEpoch, epoch);
        e.month = monthOf(epoch);
    }
}

void LogStorageManager::sampleClock() {
    const uint32_t now = millis();
    const uint64_t elapsed = uint32_t(now - _clockMillis.load()) + uint64_t(_clockRemainder.load());
    ++_clockSequence;
    _clockSeconds = _clockSeconds.load() + uint32_t(elapsed / 1000);
    _clockRemainder = uint32_t(elapsed % 1000);
    _clockMillis = now;
    ++_clockSequence;
}

uint32_t LogStorageManager::uptimeSeconds() const {
    // A bounded sequence read keeps producers independent of the writer mutex.
    for (uint8_t attempt = 0; attempt < 3; ++attempt) {
        const uint32_t sequence = _clockSequence.load();
        if (sequence & 1) continue;
        const uint32_t sampledAt = _clockMillis.load(), seconds = _clockSeconds.load();
        const uint32_t remainder = _clockRemainder.load(), now = millis();
        if (sequence == _clockSequence.load()) {
            return seconds + uint32_t((uint64_t(uint32_t(now - sampledAt)) + remainder) / 1000);
        }
    }
    return _clockSeconds.load();
}

bool LogStorageManager::enqueue(Stream stream, const char* text, uint32_t epoch, RecordType type) {
    if (stream >= StreamCount || !text || !_started) return false;
    const size_t length = strnlen(text, MAX_PAYLOAD + 1);
    if (length > MAX_PAYLOAD) { ++_stores[stream].dropped; return false; }
    Pending p = {nullptr, epoch, uptimeSeconds(), _enqueueGeneration[stream].load(), uint8_t(stream), uint8_t(type)};
    p.text = static_cast<char*>(malloc(length + 1));
    if (p.text) memcpy(p.text, text, length + 1);
    if (!p.text || xQueueSend(stream == Sys ? _diagnostic : _important, &p, 0) != pdTRUE) {
        free(p.text);
        ++_stores[stream].dropped;
        return false;
    }
    const uint32_t depth = uxQueueMessagesWaiting(_important) + uxQueueMessagesWaiting(_diagnostic);
    uint32_t previous = _queueHighWater.load();
    while (depth > previous && !_queueHighWater.compare_exchange_weak(previous, depth)) {}
    return true;
}

bool LogStorageManager::removeEntry(uint8_t stream, uint8_t index) {
    auto& s = _stores[stream];
    if (index >= s.count || !s.entries[index].sealed) return false;
    // Persist the allocation high-water mark before the last evidence of an ID is removed.
    if (!checkpoint(stream)) return false;
    if (!LITTLEFS.remove(path(stream, s.entries[index].id))) { s.state = "io_error"; return false; }
    s.bytes -= s.entries[index].size;
    for (uint8_t i = index + 1; i < s.count; ++i) s.entries[i - 1] = s.entries[i];
    --s.count; ++s.revision; ++s.catalogRevision; s.dirty = true;
    s.state = "ready";
    return true;
}

bool LogStorageManager::cleanupStep() {
    // At most one removal per tick, in diagnostic -> toggle -> power order.
    for (int i = Sys; i >= Power; --i) {
        auto& s = _stores[i];
        if (s.count && (_pressure || s.needsSpace || s.bytes > s.quota)) {
            // Keep a durable last ID even if both catalog slots need rebuilding later.
            if (s.count == 1) {
                if (!s.entries[0].sealed || !createSegment(i, s.entries[0].month)) continue;
                return true;
            }
            if (removeEntry(i, 0)) {
                s.needsSpace = false;
                _lastSpaceCheck = millis() - 5000;
                return true;
            }
        }
    }
    return false;
}

bool LogStorageManager::createSegment(uint8_t stream, uint32_t month) {
    auto& s = _stores[stream];
    if (s.count >= MAX_SEGMENTS || s.nextId == UINT32_MAX || _free < _low + HEADER_SIZE) {
        s.state = "storage_full";
        return false;
    }
    const uint32_t id = s.nextId++;
    if (!writeSegmentHeader(stream, s.generation, id)) { s.state = "io_error"; return false; }
    if (s.count) s.entries[s.count - 1].sealed = true;
    Entry e; e.id = id; e.size = HEADER_SIZE; e.month = month; e.sealed = false;
    s.entries[s.count++] = e; s.bytes += HEADER_SIZE;
    _free = _free > HEADER_SIZE ? _free - HEADER_SIZE : 0;
    ++s.catalogRevision; ++s.revision; s.dirty = true;
    return checkpoint(stream);
}

bool LogStorageManager::append(const Pending& p) {
    auto& s = _stores[p.stream];
    if (p.generation != s.generation) return false;
    const size_t bytes = RECORD_HEADER_SIZE + strlen(p.text) + 4;
    if (HEADER_SIZE + bytes > s.quota) {
        s.state = "storage_full";
        return false;
    }
    const uint32_t month = monthOf(p.epoch);
    const bool rollover = !s.count || s.entries[s.count - 1].sealed ||
        s.entries[s.count - 1].size + bytes > s.segmentLimit ||
        (p.stream == Power && month && s.entries[s.count - 1].month != month);
    const size_t growth = bytes + (rollover ? HEADER_SIZE : 0);
    if (_pressure || _free < _low + growth || s.bytes + growth > s.quota) {
        s.state = "storage_full";
        s.needsSpace = true;
        // Seal a lone full active file so maintenance can reclaim it on a later tick.
        if (s.count && !s.entries[s.count - 1].sealed) {
            s.entries[s.count - 1].sealed = true;
            ++s.catalogRevision; ++s.revision; s.dirty = true;
        }
        return false;
    }
    if (rollover) {
        // A full catalog can still append to its active segment. Evict only at rollover.
        if (s.count == MAX_SEGMENTS && !removeEntry(p.stream, 0)) return false;
        if (!createSegment(p.stream, month)) return false;
    }
    auto& e = s.entries[s.count - 1];
    const size_t n = encodeRecord(_buffer, RecordType(p.type), _boot, p.uptime, p.epoch, p.text, strlen(p.text));
    File f = LITTLEFS.open(path(p.stream, e.id), "a");
    bool ok = f && f.size() == e.size && f.write(_buffer, n) == n;
    if (f) { f.flush(); f.close(); }
    f = LITTLEFS.open(path(p.stream, e.id), "r");
    ok = ok && f && f.seek(e.size, SeekSet) && f.read(_buffer, n) == n && validRecord(_buffer, n);
    f.close();
    if (!ok) {
        e.sealed = true; ++s.catalogRevision; ++s.revision;
        s.state = "io_error"; s.dirty = true; _retryAt = millis() + 1000;
        return false;
    }
    // Only complete writes become visible to readers; never append after a failed write.
    e.size += n; s.bytes += n; ++s.revision; ++s.catalogRevision; s.dirty = true;
    _free = _free > n ? _free - n : 0;
    if (p.epoch) {
        e.minEpoch = e.minEpoch ? std::min(e.minEpoch, p.epoch) : p.epoch;
        e.maxEpoch = std::max(e.maxEpoch, p.epoch);
    }
    s.state = "ready";
    return true;
}

void LogStorageManager::work() {
    compat::StorageGuard guard(_lock);
    if (!guard) return;
    sampleClock();
    if (_retryAt && int32_t(millis() - _retryAt) < 0) return;
    _retryAt = 0;
    if (!_ready) { recoverStep(); return; }
    const uint32_t now = millis();
    if (now - _lastSpaceCheck >= 5000) {
        const uint32_t total = compat::fsTotalBytes(), used = compat::fsUsedBytes();
        _free = total > used ? total - used : 0;
        _pressure = _free < (_pressure ? _target : _low);
        _lastSpaceCheck = now;
    }
    if (cleanupStep()) return;
    // Reclaim orphaned v2 segments (old generations or entries beyond the bounded catalog).
    if (_orphanStreamsRemaining) {
      if (!_iterator) _iterator = new compat::DirIterator(directory(_recoverStream % StreamCount));
      String name; size_t size; bool dir;
      const uint8_t scanStream = _recoverStream % StreamCount;
      if (_iterator->next(name, size, dir)) {
        ++_scanEntries;
        name = name.substring(name.lastIndexOf('/') + 1);
        if (!dir && name.endsWith(".seg") && name.length() == 16) {
            uint32_t id = strtoul(name.c_str(), nullptr, 10);
            bool retained = false;
            for (uint8_t i = 0; i < _stores[scanStream].count; ++i) retained |= _stores[scanStream].entries[i].id == id;
            if (!retained && checkpoint(scanStream)) LITTLEFS.remove(String(directory(scanStream)) + "/" + name);
        }
      } else {
        _iterator->close(); delete _iterator; _iterator = nullptr; ++_recoverStream;
        --_orphanStreamsRemaining;
      }
    }
    Pending p;
    if (xQueueReceive(_important, &p, 0) == pdTRUE || xQueueReceive(_diagnostic, &p, 0) == pdTRUE) {
        if (!append(p)) ++_stores[p.stream].dropped;
        free(p.text);
    }
    if (now - _lastCheckpoint >= LOG_V2_CHECKPOINT_MS) {
        for (uint8_t s = 0; s < StreamCount; ++s) if (_stores[s].dirty) { checkpoint(s); break; }
        _lastCheckpoint = now;
    }
}

size_t LogStorageManager::size(Stream stream) {
    return stream < StreamCount ? _stores[stream].bytes.load() : 0;
}

bool LogStorageManager::clear(Stream stream) {
    compat::StorageGuard guard(_lock);
    if (!guard || !_ready || stream >= StreamCount) return false;
    auto& s = _stores[stream];
    if (s.generation == UINT32_MAX || s.nextId == UINT32_MAX || _free < _low + HEADER_SIZE) return false;
    const uint32_t generation = s.generation + 1, id = s.nextId++;
    // The empty segment is a durable generation marker, including after catalog corruption.
    if (!writeSegmentHeader(stream, generation, id)) { s.state = "io_error"; return false; }
    s.generation = generation;
    _enqueueGeneration[stream] = s.generation;
    Entry marker; marker.id = id; marker.size = HEADER_SIZE; marker.sealed = false;
    s.entries[0] = marker; s.count = 1; s.bytes = HEADER_SIZE;
    _free = _free > HEADER_SIZE ? _free - HEADER_SIZE : 0;
    ++s.revision; ++s.catalogRevision; s.dirty = true;
    checkpoint(stream);
    if (_iterator) { _iterator->close(); delete _iterator; _iterator = nullptr; }
    _orphanStreamsRemaining = StreamCount;
    return true;
}

void LogStorageManager::state(protocol::CommandResponse& r, uint8_t stream) {
    const auto& s = _stores[stream];
    r.setU8(FieldId::LogSchema, SCHEMA); r.setU8(FieldId::Stream, stream);
    r.setU32(FieldId::Generation, s.generation); r.setU32(FieldId::BootId, _boot);
    r.setU32(FieldId::Revision, s.revision); r.setU32(FieldId::CatalogRevision, s.catalogRevision);
    r.setU32(FieldId::OldestSegmentId, s.count ? s.entries[0].id : 0);
    r.setU32(FieldId::SegmentId, s.count ? s.entries[s.count - 1].id : 0);
    r.setU32(FieldId::Size, s.count ? s.entries[s.count - 1].size : 0);
    r.setU32(FieldId::LogicalBytes, s.bytes); r.setU32(FieldId::Total, s.count);
    r.setU32(FieldId::DroppedRecords, s.dropped); r.setString(FieldId::StorageState, s.state);
    r.setU32(FieldId::ScanEntries, _scanEntries); r.setU32(FieldId::ReadChunks, _readChunks);
    r.setU32(FieldId::QueueDepth, uxQueueMessagesWaiting(_important) + uxQueueMessagesWaiting(_diagnostic));
    r.setU32(FieldId::QueueHighWater, _queueHighWater);
}

void LogStorageManager::handle(protocol::CommandId command, const protocol::CommandRequest& q, protocol::CommandResponse& r) {
    compat::StorageGuard guard(_lock);
    if (!guard) { status(r, "busy"); return; }
    if (!_ready) { status(r, "recovering"); return; }
    uint32_t stream = 0;
    if (command == protocol::CommandId::GetLogState && !q.getUint(FieldId::Stream, stream)) {
        status(r, "ok"); r.setU8(FieldId::LogSchema, SCHEMA);
        auto array = r.beginArray(FieldId::Entries);
        for (uint8_t i = 0; i < StreamCount; ++i) { auto item = array->addBeginObject(); state(*item, i); array->endObject(item); }
        r.endArray(array); return;
    }
    q.getUint(FieldId::Stream, stream);
    if (stream >= StreamCount) { status(r, "invalid_stream"); return; }
    if (command == protocol::CommandId::GetLogState) { status(r, "ok"); state(r, stream); }
    else if (command == protocol::CommandId::SyncLog) sync(q, r, stream);
    else if (command == protocol::CommandId::ListLogSegments) list(q, r, stream);
    else if (command == protocol::CommandId::DeleteLogSegment) {
        uint32_t id = 0, gen = 0;
        q.getUint(FieldId::SegmentId, id); q.getUint(FieldId::Generation, gen);
        auto& s = _stores[stream];
        if (gen != s.generation) { status(r, "generation_changed"); return; }
        for (uint8_t i = 0; i < s.count; ++i) if (s.entries[i].id == id) {
            if (s.count == 1) {
                if (!createSegment(stream, s.entries[i].month)) {
                    status(r, s.state); return;
                }
            } else if (!s.entries[i].sealed) {
                // No writer can enter while this lock is held. The next append can rotate.
                s.entries[i].sealed = true;
                if (!removeEntry(stream, i)) {
                    s.entries[i].sealed = false;
                    status(r, "io_error");
                    return;
                }
                status(r, "ok");
                return;
            }
            status(r, removeEntry(stream, i) ? "ok" : "io_error"); return;
        }
        status(r, "cursor_expired");
    }
}

void LogStorageManager::list(const protocol::CommandRequest& q, protocol::CommandResponse& r, uint8_t stream) {
    const auto& s = _stores[stream];
    uint32_t offset = 0, revision = s.catalogRevision, boot = _boot;
    q.getUint(FieldId::Offset, offset); q.getUint(FieldId::CatalogRevision, revision); q.getUint(FieldId::BootId, boot);
    if (revision != s.catalogRevision || boot != _boot) { status(r, "snapshot_expired"); return; }
    status(r, "ok"); state(r, stream);
    const uint32_t end = std::min(uint32_t(s.count), offset > UINT32_MAX - MAX_SEGMENTS ? UINT32_MAX : offset + MAX_SEGMENTS);
    auto a = r.beginArray(FieldId::Entries);
    for (uint32_t i = offset; i < end; ++i) {
        auto e = a->addBeginObject(); const auto& v = s.entries[i];
        e->setU32(FieldId::SegmentId, v.id); e->setU32(FieldId::Size, v.size);
        e->setU32(FieldId::MinEpoch, v.minEpoch); e->setU32(FieldId::MaxEpoch, v.maxEpoch);
        e->setBool(FieldId::Sealed, v.sealed); a->endObject(e);
    }
    r.endArray(a); r.setBool(FieldId::More, end < s.count);
}

void LogStorageManager::sync(const protocol::CommandRequest& q, protocol::CommandResponse& r, uint8_t stream) {
    const auto& s = _stores[stream];
    uint32_t generation = 0, id = 0, offset = 0, boot = 0, target = 0, targetSize = 0, limit = CHUNK_SIZE;
    q.getUint(FieldId::Generation, generation); q.getUint(FieldId::SegmentId, id); q.getUint(FieldId::Offset, offset);
    q.getUint(FieldId::BootId, boot); q.getUint(FieldId::SnapshotId, target); q.getUint(FieldId::SnapshotSize, targetSize);
    q.getUint(FieldId::Limit, limit); limit = std::min(limit ? limit : uint32_t(CHUNK_SIZE), uint32_t(CHUNK_SIZE));
    state(r, stream);
    if (generation != s.generation) { status(r, "generation_changed"); return; }
    if (boot != _boot) { status(r, "snapshot_expired"); return; }
    if (!s.count || !target) { status(r, "up_to_date"); return; }
    if (!id) { id = s.entries[0].id; offset = 0; }
    int index = -1, targetIndex = -1;
    for (uint8_t i = 0; i < s.count; ++i) {
        if (s.entries[i].id == id) index = i;
        if (s.entries[i].id == target) targetIndex = i;
    }
    if (index < 0 || targetIndex < 0) { status(r, "cursor_expired"); return; }
    if (targetSize > s.entries[targetIndex].size || id > target || offset > s.entries[index].size) {
        status(r, "invalid_cursor"); return;
    }
    uint32_t end = id == target ? targetSize : s.entries[index].size;
    if (offset > end) { status(r, "invalid_cursor"); return; }
    if (offset == end && id == target) { status(r, "up_to_date"); return; }
    if (offset == end) {
        ++index; id = s.entries[index].id; offset = 0;
        end = id == target ? targetSize : s.entries[index].size;
    }
    File f = LITTLEFS.open(path(stream, id), "r");
    const size_t n = std::min(limit, end - offset);
    if (!f || !f.seek(offset, SeekSet) || f.read(_buffer, n) != n) { f.close(); status(r, "io_error"); return; }
    f.close();
    ++_readChunks;
    status(r, "ok"); r.setU32(FieldId::SegmentId, id); r.setU32(FieldId::Offset, offset);
    r.setU32(FieldId::NextOffset, offset + n); r.setU32(FieldId::SnapshotId, target);
    r.setU32(FieldId::SnapshotSize, targetSize); r.setBytes(FieldId::Data, _buffer, n);
    r.setBool(FieldId::Sealed, offset + n == end);
    r.setBool(FieldId::More, id != target || offset + n < targetSize);
}
