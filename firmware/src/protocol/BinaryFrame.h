#pragma once

#include <stdint.h>
#include <stddef.h>
#include "protocol/BinaryProtocol.h"
#include "protocol/BinaryCommandIds.h"

namespace protocol {

/**
 * Validates whether the given raw data is a complete and valid Binary Protocol frame.
 * Ensures the frame has at least 3 bytes [MAGIC_START, CommandId, MAGIC_END]
 * and matches the start and end delimiter markers.
 */
inline bool isValidFrame(const uint8_t* data, size_t length) {
    return data != nullptr && length >= 3 &&
           data[0] == BINARY_PROTOCOL_MAGIC &&
           data[length - 1] == BINARY_PROTOCOL_END_MAGIC;
}

/**
 * Checks if the buffer starts with the binary magic byte (0xB7).
 */
inline bool isBinaryHeader(const uint8_t* data, size_t length) {
    return data != nullptr && length >= 1 && data[0] == BINARY_PROTOCOL_MAGIC;
}

/**
 * Unpacks a binary frame into its raw command ID and payload view.
 * @param data Pointer to the complete frame buffer.
 * @param length Total length of the frame buffer.
 * @param outCmdId Output reference for the extracted 1-byte command ID.
 * @param outPayload Output pointer to the start of the payload data.
 * @param outPayloadLen Output length of the payload data.
 * @return true if valid frame, false otherwise.
 */
inline bool parseFrame(const uint8_t* data, size_t length, uint8_t& outCmdId, const uint8_t*& outPayload, size_t& outPayloadLen) {
    if (!isValidFrame(data, length)) {
        return false;
    }
    outCmdId = data[1];
    outPayload = data + 2;
    outPayloadLen = length - 3;
    return true;
}

/**
 * Overload for typed protocol::CommandId.
 */
inline bool parseFrame(const uint8_t* data, size_t length, CommandId& outCmdId, const uint8_t*& outPayload, size_t& outPayloadLen) {
    uint8_t rawCmd = 0;
    if (!parseFrame(data, length, rawCmd, outPayload, outPayloadLen)) {
        return false;
    }
    outCmdId = static_cast<CommandId>(rawCmd);
    return true;
}

/**
 * Returns pointer to the start of payload data inside a frame buffer.
 */
inline uint8_t* framePayloadBuffer(uint8_t* buffer) {
    return buffer ? (buffer + 2) : nullptr;
}

/**
 * Returns usable payload capacity given total frame buffer size.
 */
inline size_t framePayloadCapacity(size_t totalBufferSize) {
    return (totalBufferSize >= 3) ? (totalBufferSize - 3) : 0;
}

/**
 * Initializes a binary frame header with MAGIC_START and CommandId.
 * @param buffer Output buffer of at least 3 bytes.
 * @param cmdId Command ID byte.
 */
inline void initFrame(uint8_t* buffer, uint8_t cmdId) {
    if (buffer) {
        buffer[0] = BINARY_PROTOCOL_MAGIC;
        buffer[1] = cmdId;
    }
}

/**
 * Initializes a binary frame header with typed CommandId.
 */
inline void initFrame(uint8_t* buffer, CommandId cmdId) {
    initFrame(buffer, static_cast<uint8_t>(cmdId));
}

/**
 * Finalizes a binary frame by attaching MAGIC_END right after the payload.
 * @param buffer The starting address of the frame buffer.
 * @param payloadLen The number of payload bytes written into the payload buffer.
 * @return Total frame length in bytes (payloadLen + 3).
 */
inline size_t finalizeFrame(uint8_t* buffer, size_t payloadLen) {
    if (!buffer) return 0;
    size_t endIdx = 2 + payloadLen;
    buffer[endIdx] = BINARY_PROTOCOL_END_MAGIC;
    return endIdx + 1;
}

} // namespace protocol
