#pragma once

#include <stdint.h>

namespace protocol {

/**
 * Protocol Magic Bytes
 * 
 * BINARY_PROTOCOL_MAGIC indicates the start of a binary MQTT/WS packet.
 * BINARY_PROTOCOL_END_MAGIC indicates the end of a binary MQTT/WS packet, 
 * ensuring packet integrity.
 */
constexpr uint8_t BINARY_PROTOCOL_MAGIC = 0xB7;
constexpr uint8_t BINARY_PROTOCOL_END_MAGIC = 0xA5;

} // namespace protocol

#include "protocol/BinaryFrame.h"
