#include "core/log/LogStorageManager.h"
#include <cassert>
#include <iostream>
#include <map>

using protocol::FieldId;
using protocol::CommandId;
struct Request : protocol::CommandRequest {
    std::map<FieldId,uint32_t> numbers;
    bool getUint(FieldId id, uint32_t& out) const override { auto it = numbers.find(id); if (it == numbers.end()) return false; out = it->second; return true; }
    bool getInt(FieldId, int32_t&) const override { return false; }
    bool getBool(FieldId, bool&) const override { return false; }
    bool getString(FieldId, String&) const override { return false; }
    bool getFloat(FieldId, float&) const override { return false; }
    bool getDouble(FieldId, double&) const override { return false; }
    bool getBytes(FieldId, const uint8_t*&, size_t&) const override { return false; }
    bool arrayContainsString(FieldId, const char*) const override { return false; }
};
struct Response : protocol::CommandResponse {
    std::map<FieldId,uint32_t> numbers;
    std::map<FieldId,std::string> strings;
    std::vector<uint8_t> bytes;
    void setBool(FieldId id, bool v) override { numbers[id] = v; }
    void setU8(FieldId id, uint8_t v) override { numbers[id] = v; }
    void setU16(FieldId id, uint16_t v) override { numbers[id] = v; }
    void setU32(FieldId id, uint32_t v) override { numbers[id] = v; }
    void setI8(FieldId, int8_t) override {}
    void setI16(FieldId, int16_t) override {}
    void setI32(FieldId, int32_t) override {}
    void setFloat(FieldId, float) override {}
    void setDouble(FieldId, double) override {}
    void setString(FieldId id, const char* v) override { strings[id] = v; }
    void setString(FieldId, const __FlashStringHelper*) override {}
    void setString(FieldId id, const String& v) override { strings[id] = v; }
    void setBytes(FieldId, const uint8_t* p, size_t n) override { bytes.assign(p,p+n); }
    protocol::CommandResponse* beginObject(FieldId) override { return new Response; }
    protocol::CommandResponse* beginArray(FieldId) override { return new Response; }
    void endObject(protocol::CommandResponse* p) override { delete p; }
    void endArray(protocol::CommandResponse* p) override { delete p; }
    void addString(const char*) override {}
    void addString(const __FlashStringHelper*) override {}
    protocol::CommandResponse* addBeginObject() override { return new Response; }
};
Response call(CommandId command, Request q = {}) {
    Response r; LogStorageManager::instance().handle(command,q,r); return r;
}
Response state(uint8_t stream = logv2::Sys) {
    Request q; q.numbers[FieldId::Stream] = stream; return call(CommandId::GetLogState,q);
}
void step(int n = 1) { while (n--) { hostMillis += 25; hostCallback(); } }
Request cursor(const Response& s) {
    Request q;
    q.numbers[FieldId::Stream] = logv2::Sys;
    for (auto id : {FieldId::Generation,FieldId::BootId}) q.numbers[id] = s.numbers.at(id);
    q.numbers[FieldId::SegmentId] = s.numbers.at(FieldId::SegmentId);
    q.numbers[FieldId::Offset] = s.numbers.at(FieldId::Size);
    q.numbers[FieldId::SnapshotId] = s.numbers.at(FieldId::SegmentId);
    q.numbers[FieldId::SnapshotSize] = s.numbers.at(FieldId::Size);
    return q;
}
int main(int argc, char** argv) {
    assert(argc == 3); hostRoot = argv[1]; std::filesystem::create_directories(hostRoot);
    auto& manager = LogStorageManager::instance(); assert(manager.begin());
    for (int i = 0; i < 10000 && state().strings[FieldId::Status] == "recovering"; ++i) step();
    assert(state().strings[FieldId::Status] == "ok"); step(100);
    const std::string mode = argv[2];
    if (mode == "codec") {
        assert(logv2::checksum(reinterpret_cast<const uint8_t*>("123456789"),9) == 0xcbf43926);
        uint8_t bytes[1024];
        logv2::encodeHeader(bytes,logv2::Sys,1234,7);
        const char* text = u8"Đèn 🌧️";
        size_t n = logv2::encodeRecord(bytes+24,logv2::Data,42,1000,0,text,strlen(text));
        n += logv2::encodeRecord(bytes+24+n,logv2::TimeAnchor,42,2000,1700000000,"",0);
        std::ofstream fixture(hostRoot/"codec.bin",std::ios::binary);
        fixture.write(reinterpret_cast<char*>(bytes),24+n); fixture.close();
        std::cout << "CRC32 and C++/Kotlin fixture generated\n"; return 0;
    }
    if (mode == "reboot") {
        auto before = state(); assert(before.numbers[FieldId::BootId] > 1);
        const auto oldId = before.numbers[FieldId::SegmentId];
        assert(manager.enqueue(logv2::Sys,"after reboot",1700000000)); step(5);
        assert(state().numbers[FieldId::SegmentId] > oldId);
        std::cout << "reboot recovery passed\n"; return 0;
    }
    assert(manager.enqueue(logv2::Sys,"hello",1700000000)); step(5);
    const auto first = state(); assert(first.numbers.at(FieldId::Size) == 24+20+5+4);
    auto q = cursor(first);
    const auto scans = hostScanEntries, opens = hostOpens;
    for (int i = 0; i < 50; ++i) {
        assert(state().strings[FieldId::Status] == "ok");
        assert(call(CommandId::SyncLog,q).strings[FieldId::Status] == "up_to_date");
    }
    assert(scans == hostScanEntries && opens == hostOpens);
    // Snapshot does not grow when a writer appends during synchronization.
    assert(manager.enqueue(logv2::Sys,"world",1700000001)); step(5);
    assert(call(CommandId::SyncLog,q).strings[FieldId::Status] == "up_to_date");
    q.numbers[FieldId::SnapshotSize] = state().numbers[FieldId::Size];
    auto delta = call(CommandId::SyncLog,q);
    assert(delta.strings[FieldId::Status] == "ok" && delta.bytes.size() == 29);
    assert(logv2::validRecord(delta.bytes.data(),delta.bytes.size()));
    auto clockCursor = cursor(state());
    hostMillis = UINT32_MAX - 20;
    assert(manager.enqueue(logv2::Sys,"clock before")); step(2);
    assert(manager.enqueue(logv2::Sys,"clock after")); step(2);
    clockCursor.numbers[FieldId::SnapshotSize] = state().numbers[FieldId::Size];
    const auto clockData = call(CommandId::SyncLog,clockCursor).bytes;
    const auto firstClockSize = logv2::recordSize(clockData.data());
    const auto beforeSeconds = logv2::get32(clockData.data()+12);
    const auto afterSeconds = logv2::get32(clockData.data()+firstClockSize+12);
    assert(beforeSeconds > 4000000 && afterSeconds >= beforeSeconds && afterSeconds-beforeSeconds <= 1);
    q.numbers[FieldId::Offset] = UINT32_MAX;
    assert(call(CommandId::SyncLog,q).strings[FieldId::Status] == "invalid_cursor");
    // A short write is invisible and its file is sealed before the next append.
    const auto beforePartial = state(); hostPartialWrite = 7;
    assert(manager.enqueue(logv2::Sys,"partial",1700000002)); step(2);
    assert(state().numbers[FieldId::Size] == beforePartial.numbers.at(FieldId::Size));
    assert(manager.enqueue(logv2::Sys,"new segment",1700000003)); step(50);
    assert(state().numbers[FieldId::SegmentId] > beforePartial.numbers.at(FieldId::SegmentId));
    auto expired = cursor(state()); expired.numbers[FieldId::SegmentId] = 99999;
    assert(call(CommandId::SyncLog,expired).strings[FieldId::Status] == "cursor_expired");
    auto old = cursor(state()); assert(manager.clear(logv2::Sys)); step(20);
    assert(call(CommandId::SyncLog,old).strings[FieldId::Status] == "generation_changed");
    // Repeated rollover and retention keep the catalog and quota bounded.
    const std::string message(400,'x');
    for (int i = 0; i < 200; ++i) { manager.enqueue(logv2::Sys,message.c_str(),1700000000+i); step(3); }
    auto bounded = state(); assert(bounded.numbers[FieldId::Total] <= 16);
    assert(bounded.numbers[FieldId::LogicalBytes] < hostCapacity);
    // Queue pressure drops records instead of allocating an unbounded backlog.
    bool dropped = false;
    for (int i = 0; i < 20; ++i) dropped |= !manager.enqueue(logv2::Sys,"queued");
    assert(dropped); step(50);
    assert(state().numbers[FieldId::DroppedRecords] > 0);
    std::cout << "snapshot, incremental read, partial write, clear, quota, queue and no-scan checks passed\n";
}
