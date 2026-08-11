#pragma once

#include <Arduino.h>
#include <mbedtls/md.h>
#include <mbedtls/gcm.h>

#include "chip/anchor.h"

// ── Crypto dùng chung (mbedtls — có trên mọi MCU: ESP32 + LibreTiny) ──
// HMAC-SHA256 cho envelope lệnh (docs §3.2): hmac = HMAC-SHA256(controlKey, seq|ts|cmd|payload)
namespace crypto {

// HMAC-SHA256 với key thô (32B), trả về 32B digest.
inline bool hmacSha256(const uint8_t* key, size_t keyLen,
                       const uint8_t* data, size_t dataLen,
                       uint8_t out[32]) {
    const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!md) return false;
    return mbedtls_md_hmac(md, key, keyLen, data, dataLen, out) == 0;
}

// HMAC-SHA256 với key dạng hex 64 ký tự (controlKey), data chuỗi ASCII,
// trả về digest dạng hex 64 ký tự. False nếu key hex sai.
inline bool hmacSha256HexKey(const char* keyHex, const char* data, size_t dataLen,
                             char outHex[65]) {
    if (!keyHex || strlen(keyHex) != 64) return false;
    uint8_t key[32];
    for (int i = 0; i < 32; i++) {
        auto hexVal = [](char c) -> int {
            if (c >= '0' && c <= '9') return c - '0';
            if (c >= 'a' && c <= 'f') return c - 'a' + 10;
            if (c >= 'A' && c <= 'F') return c - 'A' + 10;
            return -1;
        };
        int hi = hexVal(keyHex[i * 2]);
        int lo = hexVal(keyHex[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        key[i] = (uint8_t)((hi << 4) | lo);
    }

    uint8_t mac[32];
    if (!hmacSha256(key, sizeof(key), (const uint8_t*)data, dataLen, mac)) return false;

    static const char* HEX_DIGITS = "0123456789abcdef";
    for (int i = 0; i < 32; i++) {
        outHex[i * 2] = HEX_DIGITS[mac[i] >> 4];
        outHex[i * 2 + 1] = HEX_DIGITS[mac[i] & 0x0F];
    }
    outHex[64] = '\0';
    return true;
}

// ── AES-256-GCM cho config nhạy cảm (mqttPass) ──
// key = SHA-256(seed), seed = deviceId → key = hash(dev-<id>) (bí mật chỉ
// thiết bị tự sinh + biết; không lưu pass plaintext trên flash).
// blob = IV(16) + ciphertext(32) + tag(16) = 64B — layout khớp DeviceIdentity.
inline bool cfgKeyFromSeed(const char* seed, uint8_t key[32]) {
    if (!seed || seed[0] == '\0') return false;
    const mbedtls_md_info_t* md = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
    if (!md) return false;
    return mbedtls_md(md, (const uint8_t*)seed, strlen(seed), key) == 0;
}

inline bool cfgEncryptPass(const uint8_t key[32], const char* plain, uint8_t out[64]) {
    if (!plain) return false;
    uint8_t plainBuf[32] = {0};
    strncpy((char*)plainBuf, plain, sizeof(plainBuf) - 1);

    uint8_t iv[16];
    chip::randomBytes(iv, sizeof(iv));

    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    int r = mbedtls_gcm_crypt_and_tag(&ctx, MBEDTLS_GCM_ENCRYPT, sizeof(plainBuf),
        iv, sizeof(iv), nullptr, 0, plainBuf, out + 16, 16, out + 48);
    memcpy(out, iv, sizeof(iv));
    mbedtls_gcm_free(&ctx);
    return r == 0;
}

inline bool cfgDecryptPass(const uint8_t key[32], const uint8_t blob[64], char out[32]) {
    mbedtls_gcm_context ctx;
    mbedtls_gcm_init(&ctx);
    if (mbedtls_gcm_setkey(&ctx, MBEDTLS_CIPHER_ID_AES, key, 256) != 0) {
        mbedtls_gcm_free(&ctx);
        return false;
    }
    int r = mbedtls_gcm_auth_decrypt(&ctx, 32, blob, 16, nullptr, 0,
        blob + 48, 16, blob + 16, (uint8_t*)out);
    mbedtls_gcm_free(&ctx);
    if (r != 0) return false;
    out[32 - 1] = '\0';
    return true;
}

} // namespace crypto
