#pragma once
#include <stdint.h>

namespace protocol {

/**
 * Data types supported by the TLV (Type-Length-Value) binary protocol.
 * Limits to 5 bits (0-31 max).
 */
enum class BinaryType : uint8_t {
    NULL_TYPE = 0,
    BOOL = 1,
    UINT8 = 2,
    UINT16 = 3,
    UINT32 = 4,
    UINT64 = 5,
    INT8 = 6,
    INT16 = 7,
    INT32 = 8,
    INT64 = 9,
    FLOAT32 = 10,
    FLOAT64 = 11,
    STRING = 12,
    BYTES = 13,
    OBJECT = 14,
    ARRAY = 15
};

inline bool isVariableSize(BinaryType type) {
    return type == BinaryType::STRING ||
           type == BinaryType::BYTES ||
           type == BinaryType::OBJECT ||
           type == BinaryType::ARRAY;
}

inline uint16_t getFixedTypeSize(BinaryType type) {
    switch (type) {
        case BinaryType::NULL_TYPE: return 0;
        case BinaryType::BOOL:
        case BinaryType::UINT8:
        case BinaryType::INT8: return 1;
        case BinaryType::UINT16:
        case BinaryType::INT16: return 2;
        case BinaryType::UINT32:
        case BinaryType::INT32:
        case BinaryType::FLOAT32: return 4;
        case BinaryType::UINT64:
        case BinaryType::INT64:
        case BinaryType::FLOAT64: return 8;
        default: return 0;
    }
}



/**
 * Represents internal errors encountered during serialization and deserialization.
 */
enum class BinaryError : uint8_t {
    None = 0,
    BufferTooSmall = 1,
    InvalidHeader = 2,
    InvalidType = 3,
    InvalidSize = 4,
    UnexpectedEnd = 5,
    InvalidContainer = 6,
    NestingTooDeep = 7,
    InvalidValue = 8
};

} // namespace protocol
