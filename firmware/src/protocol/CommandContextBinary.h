#pragma once
#include "protocol/CommandContext.h"
#include "protocol/BinaryReader.h"
#include "protocol/BinaryWriter.h"

namespace protocol {

class BinaryCommandRequest : public CommandRequest {
public:
    BinaryCommandRequest(const uint8_t* data, size_t size) {
        BinaryReader reader(data, size);
        FieldView field;
        if (reader.next(field) && field.id == static_cast<uint16_t>(FieldId::None) && field.type == BinaryType::OBJECT) {
            _data = field.data;
            _size = field.size;
        } else {
            _data = data;
            _size = size;
        }
    }
    
    bool findField(FieldId id, FieldView& outField) const {
        BinaryReader reader(_data, _size);
        while (reader.next(outField)) {
            if (outField.id == static_cast<uint16_t>(id)) {
                return true;
            }
        }
        return false;
    }
    
    bool getUint(FieldId id, uint32_t& out) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        if (field.type == BinaryType::UINT8) { uint8_t v; if (reader.readU8(field, v)) { out = v; return true; } }
        if (field.type == BinaryType::UINT16) { uint16_t v; if (reader.readU16(field, v)) { out = v; return true; } }
        if (field.type == BinaryType::UINT32) { return reader.readU32(field, out); }
        if (field.type == BinaryType::INT8) { int8_t v; if (reader.readI8(field, v) && v >= 0) { out = v; return true; } }
        if (field.type == BinaryType::INT16) { int16_t v; if (reader.readI16(field, v) && v >= 0) { out = v; return true; } }
        if (field.type == BinaryType::INT32) { int32_t v; if (reader.readI32(field, v) && v >= 0) { out = v; return true; } }
        return false;
    }
    
    bool getInt(FieldId id, int32_t& out) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        if (field.type == BinaryType::INT8) { int8_t v; if (reader.readI8(field, v)) { out = v; return true; } }
        if (field.type == BinaryType::INT16) { int16_t v; if (reader.readI16(field, v)) { out = v; return true; } }
        if (field.type == BinaryType::INT32) { return reader.readI32(field, out); }
        if (field.type == BinaryType::UINT8) { uint8_t v; if (reader.readU8(field, v)) { out = v; return true; } }
        if (field.type == BinaryType::UINT16) { uint16_t v; if (reader.readU16(field, v)) { out = v; return true; } }
        if (field.type == BinaryType::UINT32) { uint32_t v; if (reader.readU32(field, v)) { out = static_cast<int32_t>(v); return true; } }
        return false;
    }
    
    bool getBool(FieldId id, bool& out) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        if (field.type == BinaryType::BOOL) return reader.readBool(field, out);
        if (field.type == BinaryType::UINT8) { uint8_t v; if (reader.readU8(field, v)) { out = (v != 0); return true; } }
        if (field.type == BinaryType::INT8) { int8_t v; if (reader.readI8(field, v)) { out = (v != 0); return true; } }
        if (field.type == BinaryType::INT32) { int32_t v; if (reader.readI32(field, v)) { out = (v != 0); return true; } }
        return false;
    }
    
    bool getString(FieldId id, String& out) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        BinaryStringView str;
        if (reader.readString(field, str)) {
            char* buf = new char[str.length + 1];
            memcpy(buf, str.data, str.length);
            buf[str.length] = '\0';
            out = String(buf);
            delete[] buf;
            return true;
        }
        return false;
    }
    
    bool getFloat(FieldId id, float& out) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        if (field.type == BinaryType::FLOAT32) return reader.readFloat32(field, out);
        if (field.type == BinaryType::FLOAT64) { double v; if (reader.readFloat64(field, v)) { out = static_cast<float>(v); return true; } }
        if (field.type == BinaryType::INT32) { int32_t v; if (reader.readI32(field, v)) { out = static_cast<float>(v); return true; } }
        if (field.type == BinaryType::UINT32) { uint32_t v; if (reader.readU32(field, v)) { out = static_cast<float>(v); return true; } }
        return false;
    }

    bool getDouble(FieldId id, double& out) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        if (field.type == BinaryType::FLOAT64) return reader.readFloat64(field, out);
        if (field.type == BinaryType::FLOAT32) { float v; if (reader.readFloat32(field, v)) { out = static_cast<double>(v); return true; } }
        if (field.type == BinaryType::INT32) { int32_t v; if (reader.readI32(field, v)) { out = static_cast<double>(v); return true; } }
        if (field.type == BinaryType::UINT32) { uint32_t v; if (reader.readU32(field, v)) { out = static_cast<double>(v); return true; } }
        return false;
    }
    
    bool getBytes(FieldId id, const uint8_t*& outData, size_t& outLen) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        if (field.type == BinaryType::BYTES || field.type == BinaryType::STRING) {
            outData = field.data;
            outLen = field.size;
            return true;
        }
        return false;
    }

    bool arrayContainsString(FieldId id, const char* value) const override {
        FieldView field;
        if (!findField(id, field)) return false;
        BinaryReader reader(_data, _size);
        BinaryReader child(nullptr, 0);
        if (reader.enterArray(field, child)) {
            FieldView elem;
            while (child.next(elem)) {
                BinaryStringView str;
                if (child.readString(elem, str)) {
                    if (str.length == 3 && strncmp(str.data, "all", 3) == 0) return true;
                    if (str.length == strlen(value) && strncmp(str.data, value, str.length) == 0) return true;
                }
            }
        }
        return false;
    }
private:
    const uint8_t* _data;
    size_t _size;
};

class BinaryCommandResponse : public CommandResponse {
public:
    BinaryCommandResponse(BinaryWriter* writer, BinaryWriter::Container* container = nullptr) 
        : _writer(writer), _container(container) {}
        
    void setBool(FieldId id, bool value) override { _writer->writeBool(id, value); }
    void setU8(FieldId id, uint8_t value) override { _writer->writeU8(id, value); }
    void setU16(FieldId id, uint16_t value) override { _writer->writeU16(id, value); }
    void setU32(FieldId id, uint32_t value) override { _writer->writeU32(id, value); }
    void setI8(FieldId id, int8_t value) override { _writer->writeI8(id, value); }
    void setI16(FieldId id, int16_t value) override { _writer->writeI16(id, value); }
    void setI32(FieldId id, int32_t value) override { _writer->writeI32(id, value); }
    void setFloat(FieldId id, float value) override { _writer->writeFloat32(id, value); }
    void setDouble(FieldId id, double value) override { _writer->writeFloat64(id, value); }
    
    void setString(FieldId id, const char* value) override {
        _writer->writeString(id, value);
    }
    
    void setString(FieldId id, const String& value) override {
        _writer->writeString(id, value);
    }
    
    void setBytes(FieldId id, const uint8_t* data, size_t len) override {
        _writer->writeBytes(id, data, static_cast<uint16_t>(len));
    }
    
    CommandResponse* beginObject(FieldId id) override {
        BinaryWriter::Container* child = new BinaryWriter::Container(_writer->beginObject(id));
        return new BinaryCommandResponse(_writer, child);
    }
    
    CommandResponse* beginArray(FieldId id) override {
        BinaryWriter::Container* child = new BinaryWriter::Container(_writer->beginArray(id));
        return new BinaryCommandResponse(_writer, child);
    }
    
    void endObject(CommandResponse* obj) override {
        if (obj) {
            BinaryCommandResponse* bcr = static_cast<BinaryCommandResponse*>(obj);
            if (bcr->_container) {
                bcr->_container->end();
                delete bcr->_container;
            }
            delete bcr;
        }
    }
    
    void endArray(CommandResponse* arr) override {
        endObject(arr);
    }
    
    void addString(const char* value) override {
        _writer->writeString(FieldId::None, value);
    }
    
    CommandResponse* addBeginObject() override {
        BinaryWriter::Container* child = new BinaryWriter::Container(_writer->beginObject(FieldId::None));
        return new BinaryCommandResponse(_writer, child);
    }

    const uint8_t* rawData() const override { return _writer ? _writer->data() : nullptr; }
    size_t rawSize() const override { return _writer ? _writer->size() : 0; }

private:
    BinaryWriter* _writer;
    BinaryWriter::Container* _container;
};

} // namespace protocol
