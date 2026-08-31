#pragma once
#include <Arduino.h>
#include "protocol/BinaryFieldIds.h"

namespace protocol {

class CommandRequest {
public:
    virtual ~CommandRequest() = default;
    
    virtual bool getUint(FieldId id, uint32_t& out) const = 0;
    virtual bool getInt(FieldId id, int32_t& out) const = 0;
    virtual bool getBool(FieldId id, bool& out) const = 0;
    virtual bool getString(FieldId id, String& out) const = 0;
    virtual bool getFloat(FieldId id, float& out) const = 0;
    virtual bool getDouble(FieldId id, double& out) const = 0;
    
    virtual bool getBytes(FieldId id, const uint8_t*& outData, size_t& outLen) const = 0;
    
    virtual bool arrayContainsString(FieldId id, const char* value) const = 0;
};

class CommandResponse {
public:
    virtual ~CommandResponse() = default;
    
    virtual void setBool(FieldId id, bool value) = 0;
    virtual void setU8(FieldId id, uint8_t value) = 0;
    virtual void setU16(FieldId id, uint16_t value) = 0;
    virtual void setU32(FieldId id, uint32_t value) = 0;
    virtual void setI8(FieldId id, int8_t value) = 0;
    virtual void setI16(FieldId id, int16_t value) = 0;
    virtual void setI32(FieldId id, int32_t value) = 0;
    virtual void setFloat(FieldId id, float value) = 0;
    virtual void setDouble(FieldId id, double value) = 0;
    
    virtual void setString(FieldId id, const char* value) = 0;
    virtual void setString(FieldId id, const __FlashStringHelper* value) = 0;
    virtual void setString(FieldId id, const String& value) = 0;
    virtual void setBytes(FieldId id, const uint8_t* data, size_t len) = 0;
    
    virtual CommandResponse* beginObject(FieldId id) = 0;
    virtual CommandResponse* beginArray(FieldId id) = 0;
    virtual void endObject(CommandResponse* obj) = 0;
    virtual void endArray(CommandResponse* arr) = 0;
    
    // Add raw array element appending
    virtual void addString(const char* value) = 0;
    virtual void addString(const __FlashStringHelper* value) = 0;
    virtual CommandResponse* addBeginObject() = 0;

    // Buffer access
    virtual const uint8_t* rawData() const { return nullptr; }
    virtual size_t rawSize() const { return 0; }
};

} // namespace protocol
