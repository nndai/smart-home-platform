#pragma once
#include <stdint.h>
#include <stddef.h>
#include "protocol/BinaryProtocolTypes.h"

namespace protocol {

struct BinaryStringView {
    const char* data;
    uint16_t length;
};

struct BinaryBytesView {
    const uint8_t* data;
    uint16_t length;
};

/**
 * Represents a parsed field definition containing the ID, Data Type, Size, and a pointer to the raw Data.
 * The 16-bit ID accommodates the 9-bit width (up to 511) of the protocol.
 */
struct FieldView {
    uint16_t id;
    BinaryType type;
    uint16_t size;
    const uint8_t* data;
};

/**
 * High-performance, zero-allocation binary protocol deserializer.
 * Reads Type-Length-Value (TLV) encoded fields sequentially from a 
 * continuous byte buffer. Handles nested structures safely up to a specific depth.
 */
class BinaryReader {
public:
    /**
     * @param data Raw byte array to read from.
     * @param size Total length of the buffer.
     * @param depth Current nesting depth (used to prevent stack overflow from deeply nested payloads).
     */
    BinaryReader(const uint8_t* data, size_t size, uint8_t depth = 0);

    /**
     * @return true if the reader state is valid without any critical buffer errors.
     */
    bool valid() const;
    size_t remaining() const;
    BinaryError error() const { return _error; }

    /**
     * Parses the next TLV field header and advances the read offset.
     * 
     * @param field Populated with the parsed ID, Type, and a view into the field's binary data.
     * @return true if a field was successfully parsed, false if end of buffer or error.
     */
    bool next(FieldView& field);

    bool readU8(const FieldView& field, uint8_t& value) const;
    bool readU16(const FieldView& field, uint16_t& value) const;
    bool readU32(const FieldView& field, uint32_t& value) const;
    bool readU64(const FieldView& field, uint64_t& value) const;

    bool readI8(const FieldView& field, int8_t& value) const;
    bool readI16(const FieldView& field, int16_t& value) const;
    bool readI32(const FieldView& field, int32_t& value) const;
    bool readI64(const FieldView& field, int64_t& value) const;
    
    bool readFloat32(const FieldView& field, float& value) const;
    bool readFloat64(const FieldView& field, double& value) const;
    bool readBool(const FieldView& field, bool& value) const;

    bool readString(const FieldView& field, BinaryStringView& value) const;
    bool readBytes(const FieldView& field, BinaryBytesView& value) const;

    bool enterObject(const FieldView& field, BinaryReader& child) const;
    bool enterArray(const FieldView& field, BinaryReader& child) const;

private:
    const uint8_t* _data;
    size_t _size;
    size_t _offset;
    uint8_t _depth;
    BinaryError _error;
    
    static constexpr uint8_t MAX_NESTING_DEPTH = 8;
};

} // namespace protocol
