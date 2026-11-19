#include "LogRecordCodec.h"
#include <string.h>

namespace logv2 {
void encodeHeader(uint8_t* p, Stream stream, uint32_t generation, uint32_t id) {
    memset(p, 0, HEADER_SIZE);
    memcpy(p, "SHL2", 4);
    p[4] = SCHEMA;
    p[5] = stream;
    put32(p + 8, generation);
    put32(p + 12, id);
    put32(p + 20, checksum(p, 20));
}
bool decodeHeader(const uint8_t* p, Stream stream, uint32_t& generation, uint32_t& id) {
    if (memcmp(p, "SHL2", 4) || p[4] != SCHEMA || p[5] != stream ||
        get32(p + 20) != checksum(p, 20)) return false;
    generation = get32(p + 8);
    id = get32(p + 12);
    return generation != 0 && id != 0;
}
size_t encodeRecord(uint8_t* p, RecordType type, uint32_t boot, uint32_t uptime,
                    uint32_t epoch, const char* text, size_t length) {
    if (length > MAX_PAYLOAD) return 0;
    memset(p, 0, RECORD_HEADER_SIZE);
    p[0] = 0x52; p[1] = SCHEMA; p[2] = type;
    put32(p + 4, uint32_t(length));
    put32(p + 8, boot);
    put32(p + 12, uptime);
    put32(p + 16, epoch);
    memcpy(p + RECORD_HEADER_SIZE, text, length);
    put32(p + RECORD_HEADER_SIZE + length, checksum(p, RECORD_HEADER_SIZE + length));
    return RECORD_HEADER_SIZE + length + 4;
}
size_t recordSize(const uint8_t* p) {
    if (p[0] != 0x52 || p[1] != SCHEMA || (p[2] != Data && p[2] != TimeAnchor)) return 0;
    const uint32_t n = get32(p + 4);
    return n <= MAX_PAYLOAD ? RECORD_HEADER_SIZE + n + 4 : 0;
}
bool validRecord(const uint8_t* p, size_t n) {
    return n >= RECORD_HEADER_SIZE + 4 && recordSize(p) == n && get32(p + n - 4) == checksum(p, n - 4);
}
}
