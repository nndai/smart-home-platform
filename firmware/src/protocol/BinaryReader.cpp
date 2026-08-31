#include "protocol/BinaryReader.h"
#include <string.h>

namespace protocol {

BinaryReader::BinaryReader(const uint8_t* data, size_t size, uint8_t depth)
    : _data(data), _size(size), _offset(0), _depth(depth), _error(BinaryError::None) {
}

bool BinaryReader::valid() const {
    return _error == BinaryError::None;
}

size_t BinaryReader::remaining() const {
    return _size > _offset ? _size - _offset : 0;
}

bool BinaryReader::next(FieldView& field) {
    if (!valid() || remaining() == 0) {
        return false;
    }

    if (remaining() < 2) {
        _error = BinaryError::UnexpectedEnd;
        return false;
    }

    uint16_t header = (static_cast<uint16_t>(_data[_offset]) << 8) |
                      _data[_offset + 1];

    field.id = (header >> 7) & 0x1FF;
    field.type = static_cast<BinaryType>((header >> 3) & 0x0F);
    uint8_t sizeHigh = header & 0x07;

    _offset += 2;

    if (isVariableSize(field.type)) {
        if (remaining() < 1) {
            _error = BinaryError::UnexpectedEnd;
            return false;
        }
        uint8_t sizeLow = _data[_offset++];
        field.size = (static_cast<uint16_t>(sizeHigh) << 8) | sizeLow;
    } else {
        field.size = getFixedTypeSize(field.type);
    }

    if (remaining() < field.size) {
        _error = BinaryError::UnexpectedEnd;
        return false;
    }

    field.data = _data + _offset;
    _offset += field.size;

    return true;
}

bool BinaryReader::readU8(const FieldView& field, uint8_t& value) const {
    if (field.type != BinaryType::UINT8 || field.size != 1) return false;
    value = field.data[0];
    return true;
}

bool BinaryReader::readU16(const FieldView& field, uint16_t& value) const {
    if (field.type != BinaryType::UINT16 || field.size != 2) return false;
    value = (static_cast<uint16_t>(field.data[0]) << 8) |
             static_cast<uint16_t>(field.data[1]);
    return true;
}

bool BinaryReader::readU32(const FieldView& field, uint32_t& value) const {
    if (field.type != BinaryType::UINT32 || field.size != 4) return false;
    value = (static_cast<uint32_t>(field.data[0]) << 24) |
            (static_cast<uint32_t>(field.data[1]) << 16) |
            (static_cast<uint32_t>(field.data[2]) << 8) |
             static_cast<uint32_t>(field.data[3]);
    return true;
}

bool BinaryReader::readU64(const FieldView& field, uint64_t& value) const {
    if (field.type != BinaryType::UINT64 || field.size != 8) return false;
    value = (static_cast<uint64_t>(field.data[0]) << 56) |
            (static_cast<uint64_t>(field.data[1]) << 48) |
            (static_cast<uint64_t>(field.data[2]) << 40) |
            (static_cast<uint64_t>(field.data[3]) << 32) |
            (static_cast<uint64_t>(field.data[4]) << 24) |
            (static_cast<uint64_t>(field.data[5]) << 16) |
            (static_cast<uint64_t>(field.data[6]) << 8) |
             static_cast<uint64_t>(field.data[7]);
    return true;
}

bool BinaryReader::readI8(const FieldView& field, int8_t& value) const {
    if (field.type != BinaryType::INT8 || field.size != 1) return false;
    value = static_cast<int8_t>(field.data[0]);
    return true;
}

bool BinaryReader::readI16(const FieldView& field, int16_t& value) const {
    if (field.type != BinaryType::INT16 || field.size != 2) return false;
    value = static_cast<int16_t>(
            (static_cast<uint16_t>(field.data[0]) << 8) |
             static_cast<uint16_t>(field.data[1]));
    return true;
}

bool BinaryReader::readI32(const FieldView& field, int32_t& value) const {
    if (field.type != BinaryType::INT32 || field.size != 4) return false;
    value = static_cast<int32_t>(
            (static_cast<uint32_t>(field.data[0]) << 24) |
            (static_cast<uint32_t>(field.data[1]) << 16) |
            (static_cast<uint32_t>(field.data[2]) << 8) |
             static_cast<uint32_t>(field.data[3]));
    return true;
}

bool BinaryReader::readI64(const FieldView& field, int64_t& value) const {
    if (field.type != BinaryType::INT64 || field.size != 8) return false;
    value = static_cast<int64_t>(
            (static_cast<uint64_t>(field.data[0]) << 56) |
            (static_cast<uint64_t>(field.data[1]) << 48) |
            (static_cast<uint64_t>(field.data[2]) << 40) |
            (static_cast<uint64_t>(field.data[3]) << 32) |
            (static_cast<uint64_t>(field.data[4]) << 24) |
            (static_cast<uint64_t>(field.data[5]) << 16) |
            (static_cast<uint64_t>(field.data[6]) << 8) |
             static_cast<uint64_t>(field.data[7]));
    return true;
}

bool BinaryReader::readFloat32(const FieldView& field, float& value) const {
    if (field.type != BinaryType::FLOAT32 || field.size != 4) return false;
    uint32_t bits = (static_cast<uint32_t>(field.data[0]) << 24) |
                    (static_cast<uint32_t>(field.data[1]) << 16) |
                    (static_cast<uint32_t>(field.data[2]) << 8) |
                     static_cast<uint32_t>(field.data[3]);
    memcpy(&value, &bits, 4);
    return true;
}

bool BinaryReader::readFloat64(const FieldView& field, double& value) const {
    if (field.type != BinaryType::FLOAT64 || field.size != 8) return false;
    uint64_t bits = (static_cast<uint64_t>(field.data[0]) << 56) |
                    (static_cast<uint64_t>(field.data[1]) << 48) |
                    (static_cast<uint64_t>(field.data[2]) << 40) |
                    (static_cast<uint64_t>(field.data[3]) << 32) |
                    (static_cast<uint64_t>(field.data[4]) << 24) |
                    (static_cast<uint64_t>(field.data[5]) << 16) |
                    (static_cast<uint64_t>(field.data[6]) << 8) |
                     static_cast<uint64_t>(field.data[7]);
    memcpy(&value, &bits, 8);
    return true;
}

bool BinaryReader::readBool(const FieldView& field, bool& value) const {
    if (field.type != BinaryType::BOOL || field.size != 1) return false;
    value = (field.data[0] != 0);
    return true;
}

bool BinaryReader::readString(const FieldView& field, BinaryStringView& value) const {
    if (field.type != BinaryType::STRING) return false;
    value.data = reinterpret_cast<const char*>(field.data);
    value.length = field.size;
    return true;
}

bool BinaryReader::readBytes(const FieldView& field, BinaryBytesView& value) const {
    if (field.type != BinaryType::BYTES) return false;
    value.data = field.data;
    value.length = field.size;
    return true;
}

bool BinaryReader::enterObject(const FieldView& field, BinaryReader& child) const {
    if (field.type != BinaryType::OBJECT) return false;
    if (_depth >= MAX_NESTING_DEPTH) {
        return false;
    }
    child = BinaryReader(field.data, field.size, _depth + 1);
    return true;
}

bool BinaryReader::enterArray(const FieldView& field, BinaryReader& child) const {
    if (field.type != BinaryType::ARRAY) return false;
    if (_depth >= MAX_NESTING_DEPTH) {
        return false;
    }
    child = BinaryReader(field.data, field.size, _depth + 1);
    return true;
}

} // namespace protocol
