#include "protocol/BinaryWriter.h"
#include <stdlib.h>
#include <string.h>

namespace protocol {

BinaryWriter::BinaryWriter(size_t initialCapacity)
    : _buffer(nullptr)
    , _capacity(initialCapacity > 0 ? initialCapacity : DEFAULT_INITIAL_CAPACITY)
    , _offset(0)
    , _ownsBuffer(true)
    , _error(BinaryError::None)
    , _containerDepth(0)
{
    memset(_containerStack, 0, sizeof(_containerStack));
    _buffer = (uint8_t*)malloc(_capacity);
    if (!_buffer) {
        _capacity = 0;
        _error = BinaryError::BufferTooSmall;
    }
}

BinaryWriter::BinaryWriter(CommandId cmdId, size_t initialCapacity)
    : BinaryWriter(static_cast<uint8_t>(cmdId), initialCapacity)
{
}

BinaryWriter::BinaryWriter(uint8_t cmdId, size_t initialCapacity)
    : BinaryWriter(initialCapacity)
{
    initFrame(cmdId);
}

BinaryWriter::BinaryWriter(uint8_t* buffer, size_t capacity)
    : _buffer(buffer)
    , _capacity(capacity)
    , _offset(0)
    , _ownsBuffer(false)
    , _error(BinaryError::None)
    , _containerDepth(0)
{
    memset(_containerStack, 0, sizeof(_containerStack));
}

BinaryWriter::~BinaryWriter() {
    if (_ownsBuffer && _buffer) {
        free(_buffer);
        _buffer = nullptr;
    }
}

BinaryWriter::BinaryWriter(BinaryWriter&& other) noexcept
    : _buffer(other._buffer)
    , _capacity(other._capacity)
    , _offset(other._offset)
    , _ownsBuffer(other._ownsBuffer)
    , _error(other._error)
    , _containerDepth(other._containerDepth)
{
    memcpy(_containerStack, other._containerStack, sizeof(_containerStack));

    other._buffer = nullptr;
    other._capacity = 0;
    other._offset = 0;
    other._ownsBuffer = false;
    other._error = BinaryError::None;
    other._containerDepth = 0;
}

BinaryWriter& BinaryWriter::operator=(BinaryWriter&& other) noexcept {
    if (this != &other) {
        if (_ownsBuffer && _buffer) {
            free(_buffer);
        }
        _buffer = other._buffer;
        _capacity = other._capacity;
        _offset = other._offset;
        _ownsBuffer = other._ownsBuffer;
        _error = other._error;
        _containerDepth = other._containerDepth;
        memcpy(_containerStack, other._containerStack, sizeof(_containerStack));

        other._buffer = nullptr;
        other._capacity = 0;
        other._offset = 0;
        other._ownsBuffer = false;
        other._error = BinaryError::None;
        other._containerDepth = 0;
    }
    return *this;
}

void BinaryWriter::clear() {
    _offset = 0;
    _error = BinaryError::None;
    _containerDepth = 0;
    memset(_containerStack, 0, sizeof(_containerStack));
}

void BinaryWriter::initFrame(uint8_t cmdId) {
    clear();
    if (!_ensureCapacity(6)) return;

    _buffer[_offset++] = BINARY_PROTOCOL_MAGIC;      // [0]
    _buffer[_offset++] = cmdId;                      // [1]

    // Root Object Header (ID = None = 0, Type = OBJECT = 14, Size = 0)
    // 14 << 3 = 112 = 0x70
    uint16_t rootHdr = (static_cast<uint16_t>(BinaryType::OBJECT) << 3);
    _buffer[_offset++] = (rootHdr >> 8) & 0xFF;      // [2] = 0x00
    _buffer[_offset++] = rootHdr & 0xFF;             // [3] = 0x70
    _buffer[_offset++] = 0x00;                       // [4] = 0x00

    _containerStack[0] = 2;
    _containerDepth = 1;

    _syncContainersAndEndByte();                      // [5] = 0xA5
}

void BinaryWriter::initFrame(CommandId cmdId) {
    initFrame(static_cast<uint8_t>(cmdId));
}

size_t BinaryWriter::finalizeFrame() {
    _syncContainersAndEndByte();
    return size();
}

size_t BinaryWriter::size() const {
    if (_offset >= 5 && _containerDepth >= 1) {
        return _offset + 1; // Includes trailing MAGIC_END
    }
    return _offset;
}

const uint8_t* BinaryWriter::data() const {
    return _buffer;
}

void BinaryWriter::_syncContainersAndEndByte() {
    if (_error != BinaryError::None || !_buffer) return;

    // Update length of all open containers in reverse (innermost to outermost/root)
    for (int i = (int)_containerDepth - 1; i >= 0; i--) {
        size_t hdrPos = _containerStack[i];
        if (hdrPos + 3 <= _offset) {
            size_t actualSize = _offset - hdrPos - 3;
            if (actualSize > 2047) {
                _error = BinaryError::InvalidSize;
                return;
            }
            uint16_t sizeField = static_cast<uint16_t>(actualSize);
            _buffer[hdrPos + 1] = (_buffer[hdrPos + 1] & 0xF8) | ((sizeField >> 8) & 0x07);
            _buffer[hdrPos + 2] = sizeField & 0xFF;
        }
    }

    // Ensure space for trailing MAGIC_END
    if (_ensureCapacity(1)) {
        _buffer[_offset] = BINARY_PROTOCOL_END_MAGIC;
    }
}

bool BinaryWriter::_ensureCapacity(size_t needed) {
    if (_error != BinaryError::None) return false;
    if (_offset + needed <= _capacity) return true;

    if (!_ownsBuffer) {
        _error = BinaryError::BufferTooSmall;
        return false;
    }

    size_t required = _offset + needed;
    size_t growth = (_capacity > 0) ? _capacity : DEFAULT_INITIAL_CAPACITY;
    if (growth > MAX_GROWTH_STEP) {
        growth = MAX_GROWTH_STEP;
    }

    size_t newCap = _capacity + growth;
    if (newCap < required) {
        newCap = required;
    }

    uint8_t* newBuf = (uint8_t*)realloc(_buffer, newCap);
    if (!newBuf) {
        _error = BinaryError::BufferTooSmall;
        return false;
    }

    _buffer = newBuf;
    _capacity = newCap;
    return true;
}

bool BinaryWriter::_writeHeader(uint16_t id, BinaryType type, uint16_t size) {
    if (_error != BinaryError::None) return false;
    
    if (id > 511 || static_cast<uint8_t>(type) > 15) {
        _error = BinaryError::InvalidValue;
        return false;
    }

    if (isVariableSize(type)) {
        if (size > 2047) {
            _error = BinaryError::InvalidValue;
            return false;
        }

        if (!_ensureCapacity(3)) return false;

        uint16_t header = (id << 7) | (static_cast<uint16_t>(type) << 3) | ((size >> 8) & 0x07);

        _buffer[_offset++] = (header >> 8) & 0xFF;
        _buffer[_offset++] = header & 0xFF;
        _buffer[_offset++] = size & 0xFF;
    } else {
        if (!_ensureCapacity(2)) return false;

        uint16_t header = (id << 7) | (static_cast<uint16_t>(type) << 3);

        _buffer[_offset++] = (header >> 8) & 0xFF;
        _buffer[_offset++] = header & 0xFF;
    }

    return true;
}

bool BinaryWriter::_writeRaw(const uint8_t* data, size_t size) {
    if (_error != BinaryError::None) return false;
    if (!_ensureCapacity(size)) return false;

    if (data && size > 0) {
        memcpy(_buffer + _offset, data, size);
        _offset += size;
    }
    return true;
}

bool BinaryWriter::writeField(uint16_t id, BinaryType type, const uint8_t* data, uint16_t size) {
    if (!_writeHeader(id, type, size)) return false;
    if (size > 0 && data != nullptr) {
        if (!_writeRaw(data, size)) return false;
    }
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeNull(uint16_t id) {
    if (!_writeHeader(id, BinaryType::NULL_TYPE)) return false;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeU8(uint16_t id, uint8_t value) {
    if (!_writeHeader(id, BinaryType::UINT8)) return false;
    if (!_ensureCapacity(1)) return false;
    _buffer[_offset++] = value;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeU16(uint16_t id, uint16_t value) {
    if (!_writeHeader(id, BinaryType::UINT16)) return false;
    if (!_ensureCapacity(2)) return false;
    _buffer[_offset++] = (value >> 8) & 0xFF;
    _buffer[_offset++] = value & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeU32(uint16_t id, uint32_t value) {
    if (!_writeHeader(id, BinaryType::UINT32)) return false;
    if (!_ensureCapacity(4)) return false;
    _buffer[_offset++] = (value >> 24) & 0xFF;
    _buffer[_offset++] = (value >> 16) & 0xFF;
    _buffer[_offset++] = (value >> 8) & 0xFF;
    _buffer[_offset++] = value & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeU64(uint16_t id, uint64_t value) {
    if (!_writeHeader(id, BinaryType::UINT64)) return false;
    if (!_ensureCapacity(8)) return false;
    _buffer[_offset++] = (value >> 56) & 0xFF;
    _buffer[_offset++] = (value >> 48) & 0xFF;
    _buffer[_offset++] = (value >> 40) & 0xFF;
    _buffer[_offset++] = (value >> 32) & 0xFF;
    _buffer[_offset++] = (value >> 24) & 0xFF;
    _buffer[_offset++] = (value >> 16) & 0xFF;
    _buffer[_offset++] = (value >> 8) & 0xFF;
    _buffer[_offset++] = value & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeI8(uint16_t id, int8_t value) {
    if (!_writeHeader(id, BinaryType::INT8)) return false;
    if (!_ensureCapacity(1)) return false;
    _buffer[_offset++] = static_cast<uint8_t>(value);
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeI16(uint16_t id, int16_t value) {
    if (!_writeHeader(id, BinaryType::INT16)) return false;
    if (!_ensureCapacity(2)) return false;
    uint16_t uv = static_cast<uint16_t>(value);
    _buffer[_offset++] = (uv >> 8) & 0xFF;
    _buffer[_offset++] = uv & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeI32(uint16_t id, int32_t value) {
    if (!_writeHeader(id, BinaryType::INT32)) return false;
    if (!_ensureCapacity(4)) return false;
    uint32_t uv = static_cast<uint32_t>(value);
    _buffer[_offset++] = (uv >> 24) & 0xFF;
    _buffer[_offset++] = (uv >> 16) & 0xFF;
    _buffer[_offset++] = (uv >> 8) & 0xFF;
    _buffer[_offset++] = uv & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeI64(uint16_t id, int64_t value) {
    if (!_writeHeader(id, BinaryType::INT64)) return false;
    if (!_ensureCapacity(8)) return false;
    uint64_t uv = static_cast<uint64_t>(value);
    _buffer[_offset++] = (uv >> 56) & 0xFF;
    _buffer[_offset++] = (uv >> 48) & 0xFF;
    _buffer[_offset++] = (uv >> 40) & 0xFF;
    _buffer[_offset++] = (uv >> 32) & 0xFF;
    _buffer[_offset++] = (uv >> 24) & 0xFF;
    _buffer[_offset++] = (uv >> 16) & 0xFF;
    _buffer[_offset++] = (uv >> 8) & 0xFF;
    _buffer[_offset++] = uv & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeFloat32(uint16_t id, float value) {
    if (!_writeHeader(id, BinaryType::FLOAT32)) return false;
    if (!_ensureCapacity(4)) return false;
    uint32_t bits;
    memcpy(&bits, &value, 4);
    _buffer[_offset++] = (bits >> 24) & 0xFF;
    _buffer[_offset++] = (bits >> 16) & 0xFF;
    _buffer[_offset++] = (bits >> 8) & 0xFF;
    _buffer[_offset++] = bits & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeFloat64(uint16_t id, double value) {
    if (!_writeHeader(id, BinaryType::FLOAT64)) return false;
    if (!_ensureCapacity(8)) return false;
    uint64_t bits;
    memcpy(&bits, &value, 8);
    _buffer[_offset++] = (bits >> 56) & 0xFF;
    _buffer[_offset++] = (bits >> 48) & 0xFF;
    _buffer[_offset++] = (bits >> 40) & 0xFF;
    _buffer[_offset++] = (bits >> 32) & 0xFF;
    _buffer[_offset++] = (bits >> 24) & 0xFF;
    _buffer[_offset++] = (bits >> 16) & 0xFF;
    _buffer[_offset++] = (bits >> 8) & 0xFF;
    _buffer[_offset++] = bits & 0xFF;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeBool(uint16_t id, bool value) {
    if (!_writeHeader(id, BinaryType::BOOL)) return false;
    if (!_ensureCapacity(1)) return false;
    _buffer[_offset++] = value ? 1 : 0;
    _syncContainersAndEndByte();
    return true;
}

bool BinaryWriter::writeString(uint16_t id, const char* data, uint16_t size) {
    return writeField(id, BinaryType::STRING, reinterpret_cast<const uint8_t*>(data), size);
}

bool BinaryWriter::writeString(FieldId id, const char* data) {
    return writeString(static_cast<uint16_t>(id), data, data ? strlen(data) : 0);
}

bool BinaryWriter::writeString(FieldId id, const String& data) {
    return writeString(static_cast<uint16_t>(id), data.c_str(), data.length());
}

bool BinaryWriter::writeBytes(uint16_t id, const uint8_t* data, uint16_t size) {
    return writeField(id, BinaryType::BYTES, data, size);
}

BinaryWriter::Container::Container(BinaryWriter* writer, size_t headerOffset)
    : _writer(writer), _headerOffset(headerOffset) {
}

bool BinaryWriter::Container::end() {
    if (_writer) {
        _writer->endObject();
        return true;
    }
    return false;
}

BinaryWriter::Container BinaryWriter::beginObject(uint16_t id) {
    if (_error != BinaryError::None) return Container(nullptr, 0);
    if (_containerDepth >= MAX_CONTAINER_DEPTH) {
        _error = BinaryError::NestingTooDeep;
        return Container(nullptr, 0);
    }
    size_t pos = _offset;
    if (!_writeHeader(id, BinaryType::OBJECT, 0)) return Container(nullptr, 0);
    _containerStack[_containerDepth++] = pos;
    _syncContainersAndEndByte();
    return Container(this, pos);
}

BinaryWriter::Container BinaryWriter::beginArray(uint16_t id) {
    if (_error != BinaryError::None) return Container(nullptr, 0);
    if (_containerDepth >= MAX_CONTAINER_DEPTH) {
        _error = BinaryError::NestingTooDeep;
        return Container(nullptr, 0);
    }
    size_t pos = _offset;
    if (!_writeHeader(id, BinaryType::ARRAY, 0)) return Container(nullptr, 0);
    _containerStack[_containerDepth++] = pos;
    _syncContainersAndEndByte();
    return Container(this, pos);
}

void BinaryWriter::endObject() {
    if (_containerDepth > 1) {
        _containerDepth--;
        _syncContainersAndEndByte();
    }
}

void BinaryWriter::endArray() {
    endObject();
}

} // namespace protocol
