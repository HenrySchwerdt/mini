/* UTF-8 <-> UTF-16 conversion. */
#include "internal.h"

size_t mk_utf8_decode(const uint8_t *p, const uint8_t *end, uint32_t *cp) {
    uint8_t b = p[0];
    if (b < 0x80) {
        *cp = b;
        return 1;
    }
    uint32_t need, value;
    uint8_t lo = 0x80, hi = 0xBF;
    if (b >= 0xC2 && b <= 0xDF) {
        need = 1;
        value = b & 0x1Fu;
    } else if (b >= 0xE0 && b <= 0xEF) {
        need = 2;
        value = b & 0x0Fu;
        if (b == 0xE0) lo = 0xA0;
        if (b == 0xED) hi = 0x9F; /* no encoded surrogates */
    } else if (b >= 0xF0 && b <= 0xF4) {
        need = 3;
        value = b & 0x07u;
        if (b == 0xF0) lo = 0x90;
        if (b == 0xF4) hi = 0x8F; /* <= U+10FFFF */
    } else {
        *cp = 0xFFFD;
        return 1;
    }
    size_t i = 1;
    for (; i <= need; i++) {
        if (p + i >= end || p[i] < lo || p[i] > hi) {
            *cp = 0xFFFD;
            return i;
        }
        value = (value << 6) | (p[i] & 0x3Fu);
        lo = 0x80;
        hi = 0xBF;
    }
    *cp = value;
    return i;
}

char *mk_utf16_to_utf8(const MkChar *s, size_t n, size_t *out_length) {
    if (n > (SIZE_MAX - 1) / 3) mk_panic("string too large");
    char *out = malloc(n * 3 + 1);
    if (out == NULL) mk_panic("out of memory");
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        uint32_t c = s[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < n && s[i + 1] >= 0xDC00 && s[i + 1] <= 0xDFFF) {
            c = 0x10000u + ((c - 0xD800u) << 10) + (s[i + 1] - 0xDC00u);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        if (c < 0x80) {
            out[o++] = (char)c;
        } else if (c < 0x800) {
            out[o++] = (char)(0xC0 | (c >> 6));
            out[o++] = (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out[o++] = (char)(0xE0 | (c >> 12));
            out[o++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[o++] = (char)(0x80 | (c & 0x3F));
        } else {
            out[o++] = (char)(0xF0 | (c >> 18));
            out[o++] = (char)(0x80 | ((c >> 12) & 0x3F));
            out[o++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[o++] = (char)(0x80 | (c & 0x3F));
        }
    }
    out[o] = '\0';
    if (out_length) *out_length = o;
    return out;
}
