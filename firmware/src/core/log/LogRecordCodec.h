#pragma once
#include <stdint.h>
#include <stddef.h>

namespace logv2 {
constexpr size_t HEADER_SIZE = 24;
constexpr size_t RECORD_HEADER_SIZE = 20;
constexpr size_t MAX_PAYLOAD = 400;
constexpr size_t MAX_RECORD = RECORD_HEADER_SIZE + MAX_PAYLOAD + 4;
constexpr uint8_t SCHEMA = 2;
enum Stream : uint8_t { Power = 0, Toggle = 1, Sys = 2, StreamCount = 3 };
enum RecordType : uint8_t { Data = 1, TimeAnchor = 2 };

inline uint32_t get32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
inline void put32(uint8_t* p, uint32_t n) {
    for (uint8_t i = 0; i < 4; ++i) p[i] = uint8_t(n >> (i * 8));
}
inline uint32_t checksum(const uint8_t* p, size_t n) {
    uint32_t crc = 0xffffffff;
    while (n--) {
        crc ^= *p++;
        for (uint8_t i = 0; i < 8; ++i) crc = (crc >> 1) ^ (0xedb88320u & (0u - (crc & 1)));
    }
    return ~crc;
}
void encodeHeader(uint8_t* out, Stream stream, uint32_t generation, uint32_t id);
bool decodeHeader(const uint8_t* in, Stream stream, uint32_t& generation, uint32_t& id);
size_t encodeRecord(uint8_t* out, RecordType type, uint32_t boot, uint32_t uptime,
                    uint32_t epoch, const char* text, size_t length);
// Returns zero for an incomplete, oversized or corrupt record.
size_t recordSize(const uint8_t* header);
bool validRecord(const uint8_t* data, size_t size);
}
