#pragma once

#include <stdint.h>
#include <stddef.h>
#include <WString.h>
#include "protocol/BinaryProtocolTypes.h"
#include "protocol/BinaryProtocol.h"
#include "protocol/BinaryCommandIds.h"
#include "protocol/BinaryFieldIds.h"

namespace protocol {

/**
 * High-performance, self-growing dynamic binary protocol serializer.
 * Real-time Auto-Header & Ready-to-Send:
 * - Automatically opens Root Object on initialization (when created with CommandId).
 * - Every write operation immediately syncs all parent container lengths and places MAGIC_END (0xA5) at the end.
 * - `data()` and `size()` are ALWAYS 100% valid and ready to transmit without needing to call `end()` or `finalizeFrame()`.
 */
class BinaryWriter {
public:
    static constexpr size_t DEFAULT_INITIAL_CAPACITY = 100;
    static constexpr size_t MAX_GROWTH_STEP = 500;
    static constexpr uint8_t MAX_CONTAINER_DEPTH = 8;

    /**
     * Default dynamic-allocation constructor.
     * Starts with initialCapacity (default 100 bytes) and grows dynamically.
     */
    explicit BinaryWriter(size_t initialCapacity = DEFAULT_INITIAL_CAPACITY);

    /**
     * Convenience constructor that automatically initializes a binary frame with CommandId
     * and opens a Root Object header. The frame is immediately valid and ready to transmit.
     */
    explicit BinaryWriter(CommandId cmdId, size_t initialCapacity = DEFAULT_INITIAL_CAPACITY);
    explicit BinaryWriter(uint8_t cmdId, size_t initialCapacity = DEFAULT_INITIAL_CAPACITY);

    /**
     * Non-owning constructor wrapping an existing external buffer without dynamic growth.
     */
    BinaryWriter(uint8_t* buffer, size_t capacity);

    ~BinaryWriter();

    // Disable copying to prevent double-free
    BinaryWriter(const BinaryWriter&) = delete;
    BinaryWriter& operator=(const BinaryWriter&) = delete;

    // Move semantics support
    BinaryWriter(BinaryWriter&& other) noexcept;
    BinaryWriter& operator=(BinaryWriter&& other) noexcept;

    /**
     * Initializes a binary frame header: writes MAGIC_START (0xB7) + CommandId + Root Object Header.
     */
    void initFrame(uint8_t cmdId);
    void initFrame(CommandId cmdId);

    /**
     * Finalizes the binary frame (optional; all write calls already keep the frame ready).
     * @return Total length of the frame including magic bytes and payload.
     */
    size_t finalizeFrame();

    /**
     * Generic write mechanism for a full TLV block.
     * 
     * @param id The 9-bit field identifier (0-511)
     * @param type The 5-bit field data type
     * @param data Pointer to the binary payload data
     * @param size Length of the binary payload
     */
    bool writeField(uint16_t id, BinaryType type, const uint8_t* data, uint16_t size);
    bool writeField(FieldId id, BinaryType type, const uint8_t* data, uint16_t size) {
        return writeField(static_cast<uint16_t>(id), type, data, size);
    }

    bool writeNull(uint16_t id = 0);
    bool writeNull(FieldId id) { return writeNull(static_cast<uint16_t>(id)); }

    bool writeU8(uint16_t id, uint8_t value);
    bool writeU8(FieldId id, uint8_t value) { return writeU8(static_cast<uint16_t>(id), value); }

    bool writeU16(uint16_t id, uint16_t value);
    bool writeU16(FieldId id, uint16_t value) { return writeU16(static_cast<uint16_t>(id), value); }

    bool writeU32(uint16_t id, uint32_t value);
    bool writeU32(FieldId id, uint32_t value) { return writeU32(static_cast<uint16_t>(id), value); }

    bool writeU64(uint16_t id, uint64_t value);
    bool writeU64(FieldId id, uint64_t value) { return writeU64(static_cast<uint16_t>(id), value); }

    bool writeI8(uint16_t id, int8_t value);
    bool writeI8(FieldId id, int8_t value) { return writeI8(static_cast<uint16_t>(id), value); }

    bool writeI16(uint16_t id, int16_t value);
    bool writeI16(FieldId id, int16_t value) { return writeI16(static_cast<uint16_t>(id), value); }

    bool writeI32(uint16_t id, int32_t value);
    bool writeI32(FieldId id, int32_t value) { return writeI32(static_cast<uint16_t>(id), value); }

    bool writeI64(uint16_t id, int64_t value);
    bool writeI64(FieldId id, int64_t value) { return writeI64(static_cast<uint16_t>(id), value); }
    
    bool writeFloat32(uint16_t id, float value);
    bool writeFloat32(FieldId id, float value) { return writeFloat32(static_cast<uint16_t>(id), value); }

    bool writeFloat64(uint16_t id, double value);
    bool writeFloat64(FieldId id, double value) { return writeFloat64(static_cast<uint16_t>(id), value); }

    bool writeBool(uint16_t id, bool value);
    bool writeBool(FieldId id, bool value) { return writeBool(static_cast<uint16_t>(id), value); }

    bool writeString(uint16_t id, const char* data, uint16_t size);
    bool writeString(FieldId id, const char* data, uint16_t size) {
        return writeString(static_cast<uint16_t>(id), data, size);
    }
    bool writeString(FieldId id, const char* data);
    bool writeString(FieldId id, const String& data);

    bool writeBytes(uint16_t id, const uint8_t* data, uint16_t size);
    bool writeBytes(FieldId id, const uint8_t* data, uint16_t size) {
        return writeBytes(static_cast<uint16_t>(id), data, size);
    }

    /**
     * Container tracking for nested Objects and Arrays.
     * With Real-Time Auto-Header sync, calling end() is completely optional.
     */
    class Container {
    public:
        Container(BinaryWriter* writer = nullptr, size_t headerOffset = 0);
        bool end();
    private:
        BinaryWriter* _writer;
        size_t _headerOffset;
    };

    Container beginObject(uint16_t id = 0);
    Container beginObject(FieldId id) { return beginObject(static_cast<uint16_t>(id)); }

    Container beginArray(uint16_t id = 0);
    Container beginArray(FieldId id) { return beginArray(static_cast<uint16_t>(id)); }

    void endObject();
    void endArray();

    size_t size() const;
    size_t capacity() const { return _capacity; }
    const uint8_t* data() const;
    uint8_t* data() { return _buffer; }
    BinaryError error() const { return _error; }

    void clear();

private:
    friend class Container;

    uint8_t* _buffer;
    size_t _capacity;
    size_t _offset;
    bool _ownsBuffer;
    BinaryError _error;

    size_t _containerStack[MAX_CONTAINER_DEPTH];
    uint8_t _containerDepth;

    bool _ensureCapacity(size_t needed);
    bool _writeHeader(uint16_t id, BinaryType type, uint16_t size = 0);
    bool _writeRaw(const uint8_t* data, size_t size);
    void _syncContainersAndEndByte();
};

} // namespace protocol
