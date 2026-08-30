#pragma once

#include <Arduino.h>
#include <mbedtls/md.h>
#include <mbedtls/gcm.h>
#include <mbedtls/base64.h>

#include "chip/anchor.h"

/**
 * Unified Cryptographic & Byte Encoding Utilities for Smart Home Firmware.
 * Built on mbedtls — platform-agnostic across ESP32, ESP8266, and LibreTiny (LN882H).
 */
namespace crypto {

// ── 1. Hex Encoding & Decoding ──

/**
 * Encodes binary data into lowercase hexadecimal string.
 * @param data Pointer to source binary buffer.
 * @param len Length of source data in bytes.
 * @param out Destination buffer (must have capacity for at least len * 2 + 1 bytes).
 */
inline void hexEncode(const uint8_t* data, size_t len, char* out) {
    if (!data || !out) return;
    static const char* HEX_CHARS = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        out[i * 2]     = HEX_CHARS[data[i] >> 4];
        out[i * 2 + 1] = HEX_CHARS[data[i] & 0x0F];
    }
    out[len * 2] = '\0';
}

/**
 * Decodes a hexadecimal string into binary buffer.
 * @param hex Source null-terminated hex string.
 * @param out Destination binary buffer.
 * @param maxLen Maximum capacity of the destination buffer in bytes.
 * @param outLen Optional pointer to store the decoded byte count.
 * @return true if decoding succeeded; false on odd length, buffer overflow, or invalid hex chars.
 */
inline bool hexDecode(const char* hex, uint8_t* out, size_t maxLen, size_t* outLen = nullptr) {
    if (!hex || !out) return false;
    size_t n = strlen(hex);
    if (n % 2 != 0 || n / 2 > maxLen) return false;

    auto hexVal = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };

    for (size_t i = 0; i < n / 2; i++) {
        int hi = hexVal(hex[i * 2]);
        int lo = hexVal(hex[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        out[i] = (uint8_t)((hi << 4) | lo);
    }
    if (outLen) *outLen = n / 2;
    return true;
}

// ── 2. Hashing & HMAC (SHA-256) ──

/**
 * Computes raw SHA-256 digest of input data.
 * @param data Pointer to input data.
 * @param len Length of input data in bytes.
 * @param out Output buffer of 32 bytes for the SHA-256 digest.
 */
inline bool sha256(const uint8_t* data, size_t len, uint8_t out[32]) {
    if (!data || !out) return false;
    const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!md) return false;
    return mbedtls_md(md, data, len, out) == 0;
}

/**
 * Computes HMAC-SHA256 with binary key.
 * @param key Binary secret key.
 * @param keyLen Key length in bytes.
 * @param data Input data buffer.
 * @param dataLen Input data length in bytes.
 * @param out Output buffer of 32 bytes for the HMAC digest.
 */
inline bool hmacSha256(const uint8_t* key, size_t keyLen,
                       const uint8_t* data, size_t dataLen,
                       uint8_t out[32]) {
    if (!key || !data || !out) return false;
    const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!md) return false;
    return mbedtls_md_hmac(md, key, keyLen, data, dataLen, out) == 0;
}

/**
 * Computes HMAC-SHA256 with a 64-character hex key (controlKey).
 * @param keyHex 64-character hex string representing the 32-byte secret key.
 * @param data Input data string.
 * @param dataLen Length of input data string.
 * @param outHex Output buffer of at least 65 characters for the resulting hex digest.
 */
inline bool hmacSha256HexKey(const char* keyHex, const char* data, size_t dataLen,
                             char outHex[65]) {
    if (!keyHex || !data || !outHex || strlen(keyHex) != 64) return false;
    uint8_t key[32];
    if (!hexDecode(keyHex, key, sizeof(key))) return false;

    uint8_t mac[32];
    if (!hmacSha256(key, sizeof(key), (const uint8_t*)data, dataLen, mac)) return false;

    hexEncode(mac, sizeof(mac), outHex);
    return true;
}

/**
 * Builds the canonical string for MQTT envelope HMAC signing: "ts|cmd|payload|src"
 */
inline String buildCanonical(uint32_t ts, const char* cmd, const String& payloadStr, const char* src = "") {
    String canonical;
    canonical.reserve(32 + payloadStr.length() + (src ? strlen(src) : 0));
    canonical += String(ts);
    canonical += '|';
    if (cmd) canonical += cmd;
    canonical += '|';
    canonical += payloadStr;
    canonical += '|';
    if (src) canonical += src;
    return canonical;
}

// ── 3. Base64 Encoding & Decoding ──

/**
 * Encodes binary data to Base64 String.
 */
inline String base64Encode(const uint8_t* src, size_t len) {
    if (!src || len == 0) return "";
    size_t olen = 0;
    mbedtls_base64_encode(nullptr, 0, &olen, src, len);
    if (olen == 0) return "";

    char* buf = (char*)malloc(olen + 1);
    if (!buf) return "";

    String out;
    if (mbedtls_base64_encode((uint8_t*)buf, olen + 1, &olen, src, len) == 0) {
        buf[olen] = '\0';
        out = buf;
    }
    free(buf);
    return out;
}

/**
 * Decodes a Base64 string into a binary buffer.
 */
inline bool base64Decode(const char* src, size_t srcLen, uint8_t* out, size_t maxOutLen, size_t* outLen = nullptr) {
    if (!src || srcLen == 0 || !out) return false;
    size_t olen = 0;
    int r = mbedtls_base64_decode(out, maxOutLen, &olen, (const unsigned char*)src, srcLen);
    if (r == 0) {
        if (outLen) *outLen = olen;
        return true;
    }
    return false;
}

// ── 4. Symmetric Encryption (AES-256-GCM) ──
// Blob format (64B): [ IV (16B) | Ciphertext (32B) | Tag (16B) ]

/**
 * Derives a 32-byte AES key from a string seed (e.g. deviceId + FW_SECRET).
 */
inline bool cfgKeyFromSeed(const char* seed, uint8_t key[32]) {
    if (!seed || seed[0] == '\0') return false;
    return sha256((const uint8_t*)seed, strlen(seed), key);
}

/**
 * Encrypts data (up to 32 bytes) with AES-256-GCM into a 64-byte blob [IV(16) + CT(32) + Tag(16)].
 */
inline bool aesGcmEncrypt(const uint8_t key[32], const uint8_t* plain, size_t plainLen,
                          uint8_t out[64]) {
    if (!key || !plain || !out || plainLen > 32) return false;
    uint8_t iv[16];
    chip::randomBytes(iv, sizeof(iv));
    memcpy(out, iv, sizeof(iv));

    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    int r = mbedtls_gcm_crypt_and_tag(&ctx, MBEDTLS_GCM_ENCRYPT, plainLen,
        iv, sizeof(iv), nullptr, 0, plain, out + 16, 16, out + 48);
    mbedtls_gcm_free(&ctx);
    return r == 0;
}

/**
 * Decrypts a 64-byte AES-256-GCM blob [IV(16) + CT(32) + Tag(16)] and verifies the authentication tag.
 */
inline bool aesGcmDecrypt(const uint8_t key[32], const uint8_t blob[64],
                          uint8_t* out, size_t outLen) {
    if (!key || !blob || !out) return false;
    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    int r = mbedtls_gcm_auth_decrypt(&ctx, outLen, blob, 16, nullptr, 0,
        blob + 48, 16, blob + 16, out);
    mbedtls_gcm_free(&ctx);
    return r == 0;
}

/**
 * Helper to encrypt a 32-byte secret key (controlKey / targetKey) into a 64-byte blob.
 */
inline bool encryptKey(const uint8_t key[32], const uint8_t plain[32], uint8_t outBlob[64]) {
    return aesGcmEncrypt(key, plain, 32, outBlob);
}

/**
 * Helper to decrypt a 64-byte blob back to a 32-byte secret key with authentication verification.
 */
inline bool decryptKey(const uint8_t key[32], const uint8_t inBlob[64], uint8_t outPlain[32]) {
    return aesGcmDecrypt(key, inBlob, outPlain, 32);
}

/**
 * Encrypts a null-terminated password string into a 64-byte blob.
 */
inline bool cfgEncryptPass(const uint8_t key[32], const char* plain, uint8_t out[64]) {
    if (!plain) return false;
    uint8_t plainBuf[32] = {0};
    strncpy((char*)plainBuf, plain, sizeof(plainBuf) - 1);
    return aesGcmEncrypt(key, plainBuf, sizeof(plainBuf), out);
}

/**
 * Decrypts a 64-byte blob into a null-terminated password string.
 */
inline bool cfgDecryptPass(const uint8_t key[32], const uint8_t blob[64], char out[32]) {
    if (!aesGcmDecrypt(key, blob, (uint8_t*)out, 32)) return false;
    out[32 - 1] = '\0';
    return true;
}

} // namespace crypto
