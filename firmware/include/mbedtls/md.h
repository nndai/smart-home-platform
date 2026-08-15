#pragma once

#if !defined(ARDUINO_ARCH_ESP8266)
#include_next <mbedtls/md.h>
#else

#include <stddef.h>
#include <stdint.h>
#include <bearssl/bearssl.h>

#define MBEDTLS_MD_SHA256 1

typedef struct {
    int type;
} mbedtls_md_info_t;

static const mbedtls_md_info_t s_md_sha256_info = { MBEDTLS_MD_SHA256 };

inline const mbedtls_md_info_t* mbedtls_md_info_from_type(int type) {
    (void)type;
    return &s_md_sha256_info;
}

inline int mbedtls_md(const mbedtls_md_info_t* md, const unsigned char *input, size_t ilen, unsigned char *output) {
    (void)md;
    br_sha256_context ctx;
    br_sha256_init(&ctx);
    br_sha256_update(&ctx, input, ilen);
    br_sha256_out(&ctx, output);
    return 0;
}

inline int mbedtls_md_hmac(const mbedtls_md_info_t* md, const unsigned char *key, size_t keylen, const unsigned char *input, size_t ilen, unsigned char *output) {
    (void)md;
    br_hmac_key_context kc;
    br_hmac_context ctx;
    br_hmac_key_init(&kc, &br_sha256_vtable, key, keylen);
    br_hmac_init(&ctx, &kc, 32);
    br_hmac_update(&ctx, input, ilen);
    br_hmac_out(&ctx, output);
    return 0;
}

#endif
