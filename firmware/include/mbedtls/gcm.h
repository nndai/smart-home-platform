#pragma once

#if !defined(ARDUINO_ARCH_ESP8266)
#include_next <mbedtls/gcm.h>
#else

#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <bearssl/bearssl.h>

#define MBEDTLS_CIPHER_ID_AES 1
#define MBEDTLS_GCM_ENCRYPT 1
#define MBEDTLS_GCM_DECRYPT 0

typedef struct {
    br_aes_ct_ctr_keys aes_ctx;
    br_gcm_context gcm_ctx;
    uint8_t key[32];
    size_t key_len;
} mbedtls_gcm_context;

inline void mbedtls_gcm_init(mbedtls_gcm_context *ctx) {
    memset(ctx, 0, sizeof(*ctx));
}

inline int mbedtls_gcm_setkey(mbedtls_gcm_context *ctx, int cipher, const unsigned char *key, unsigned int keybits) {
    (void)cipher;
    size_t klen = keybits / 8;
    if (klen > 32) return -1;
    ctx->key_len = klen;
    memcpy(ctx->key, key, klen);
    br_aes_ct_ctr_init(&ctx->aes_ctx, key, klen);
    br_gcm_init(&ctx->gcm_ctx, &ctx->aes_ctx.vtable, &br_ghash_ctmul32);
    return 0;
}

inline int mbedtls_gcm_crypt_and_tag(mbedtls_gcm_context *ctx, int mode, size_t length,
                                     const unsigned char *iv, size_t iv_len,
                                     const unsigned char *add, size_t add_len,
                                     const unsigned char *input, unsigned char *output,
                                     size_t tag_len, unsigned char *tag) {
    br_gcm_reset(&ctx->gcm_ctx, iv, iv_len);
    if (add_len > 0) br_gcm_aad_inject(&ctx->gcm_ctx, add, add_len);
    br_gcm_flip(&ctx->gcm_ctx);
    if (input != output && length > 0) memcpy(output, input, length);
    if (length > 0) br_gcm_run(&ctx->gcm_ctx, (mode == MBEDTLS_GCM_ENCRYPT ? 1 : 0), output, length);
    if (tag && tag_len > 0) br_gcm_get_tag(&ctx->gcm_ctx, tag);
    return 0;
}

inline int mbedtls_gcm_auth_decrypt(mbedtls_gcm_context *ctx, size_t length,
                                    const unsigned char *iv, size_t iv_len,
                                    const unsigned char *add, size_t add_len,
                                    const unsigned char *tag, size_t tag_len,
                                    const unsigned char *input, unsigned char *output) {
    uint8_t calc_tag[16] = {0};
    mbedtls_gcm_crypt_and_tag(ctx, MBEDTLS_GCM_DECRYPT, length, iv, iv_len, add, add_len, input, output, tag_len, calc_tag);
    if (memcmp(calc_tag, tag, tag_len > 16 ? 16 : tag_len) != 0) {
        return -1;
    }
    return 0;
}

inline void mbedtls_gcm_free(mbedtls_gcm_context *ctx) {
    (void)ctx;
}

#endif
