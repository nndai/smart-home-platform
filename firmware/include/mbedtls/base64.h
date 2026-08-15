#pragma once

#if !defined(ARDUINO_ARCH_ESP8266)
#include_next <mbedtls/base64.h>
#else

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

inline int mbedtls_base64_encode(unsigned char *dst, size_t dlen, size_t *olen, const unsigned char *src, size_t slen) {
    static const char b64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t req = 4 * ((slen + 2) / 3);
    if (olen) *olen = req;
    if (!dst || dlen < req + 1) return -1;

    size_t i = 0, j = 0;
    while (i < slen) {
        uint32_t octet_a = i < slen ? src[i++] : 0;
        uint32_t octet_b = i < slen ? src[i++] : 0;
        uint32_t octet_c = i < slen ? src[i++] : 0;
        uint32_t triple = (octet_a << 16) + (octet_b << 8) + octet_c;

        dst[j++] = b64[(triple >> 18) & 0x3F];
        dst[j++] = b64[(triple >> 12) & 0x3F];
        dst[j++] = (i > slen + 1) ? '=' : b64[(triple >> 6) & 0x3F];
        dst[j++] = (i > slen) ? '=' : b64[triple & 0x3F];
    }
    dst[j] = '\0';
    return 0;
}

inline int mbedtls_base64_decode(unsigned char *dst, size_t dlen, size_t *olen, const unsigned char *src, size_t slen) {
    static const int8_t dec[] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1
    };

    size_t i = 0, j = 0;
    while (i < slen) {
        while (i < slen && (src[i] == ' ' || src[i] == '\r' || src[i] == '\n' || src[i] == '\t')) i++;
        if (i >= slen || src[i] == '=') break;
        int a = (src[i] < 128) ? dec[src[i]] : -1; i++;
        if (i >= slen || src[i] == '=') break;
        int b = (src[i] < 128) ? dec[src[i]] : -1; i++;
        int c = (i < slen && src[i] != '=') ? dec[src[i]] : 0; if (i < slen && src[i] != '=') i++;
        int d = (i < slen && src[i] != '=') ? dec[src[i]] : 0; if (i < slen && src[i] != '=') i++;

        if (a < 0 || b < 0 || c < 0 || d < 0) return -1;
        uint32_t triple = (a << 18) + (b << 12) + (c << 6) + d;
        if (dst && j < dlen) dst[j++] = (triple >> 16) & 0xFF;
        if (dst && j < dlen && src[i-2] != '=') dst[j++] = (triple >> 8) & 0xFF;
        if (dst && j < dlen && src[i-1] != '=') dst[j++] = triple & 0xFF;
    }
    if (olen) *olen = j;
    return 0;
}

#ifdef __cplusplus
}
#endif

#endif
