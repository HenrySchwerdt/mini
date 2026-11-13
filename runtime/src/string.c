/* kotlin.String: immutable UTF-16 strings. */
#include "internal.h"
#include "alloc_small.h"

#include <inttypes.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

const MkTypeInfo mk_type_String = {
    "kotlin.String", sizeof(MkString), &mk_type_Any, NULL, NULL, 0, NULL, 0, NULL, NULL, NULL
};

static MkString *string_alloc(size_t length) {
    if (length > (size_t)INT32_MAX) mk_panic("string too long");
    size_t size = sizeof(MkString) + length * sizeof(MkChar);
    MkObject *o = NULL;
#if MK_SMALL_ALLOC
    if (size <= MK_SMALL_MAX) o = mk_small_new_plain(&mk_type_String, size, 0u);
#endif
    if (o == NULL) o = mk_alloc_sized_plain(&mk_type_String, size);
    if (o == NULL) mk_panic("out of memory");
    MkString *s = (MkString *)o;
    s->length = (MkInt)length;
    return s;
}

/* A Latin-1 string: one byte per character (the characters start at the same offset as the UTF-16 ones). */
static inline MkString *string_alloc8(size_t length) {
    if (length > (size_t)INT32_MAX) mk_panic("string too long");
    size_t size = offsetof(MkString, chars) + length;
    if (size < sizeof(MkObject)) size = sizeof(MkObject);
    MkObject *o = NULL;
#if MK_SMALL_ALLOC
    if (size <= MK_SMALL_MAX) o = mk_small_new_plain(&mk_type_String, size, MK_FLAG_LATIN1);
#endif
    if (o == NULL) {
        o = mk_alloc_sized_plain(&mk_type_String, size);
        if (o == NULL) mk_panic("out of memory");
        o->flags |= MK_FLAG_LATIN1;
    }
    MkString *s = (MkString *)o;
    s->length = (MkInt)length;
    return s;
}

MkString *mk_string_alloc(MkInt length) {
    if (length < 0) mk_panic("negative string length");
    return string_alloc((size_t)length);
}

MkString *mk_string_alloc8(MkInt length) {
    if (length < 0) mk_panic("negative string length");
    return string_alloc8((size_t)length);
}

/* Copies `n` bytes (the ranges do not overlap); a short text without a libc call: two overlapping loads and stores. */
static inline void copy_short(uint8_t *d, const uint8_t *s, size_t n) {
    if (n > 16) { memcpy(d, s, n); return; }
    if (n >= 8) { uint64_t a, b; memcpy(&a, s, 8); memcpy(&b, s + n - 8, 8); memcpy(d, &a, 8); memcpy(d + n - 8, &b, 8); return; }
    if (n >= 4) { uint32_t a, b; memcpy(&a, s, 4); memcpy(&b, s + n - 4, 4); memcpy(d, &a, 4); memcpy(d + n - 4, &b, 4); return; }
    if (n >= 2) { uint16_t a, b; memcpy(&a, s, 2); memcpy(&b, s + n - 2, 2); memcpy(d, &a, 2); memcpy(d + n - 2, &b, 2); return; }
    if (n == 1) d[0] = s[0];
}

MkString *mk_string_substring(const MkString *s, MkInt start, MkInt end) {
    if (s == NULL) mk_panic("NullPointerException");
    if (start < 0 || end > s->length || start > end) mk_panic("StringIndexOutOfBoundsException: bad range");
    if (mk_string_is_latin1(s)) {
        MkString *r = string_alloc8((size_t)(end - start));
        copy_short((uint8_t *)(void *)r->chars, mk_string_bytes(s) + start, (size_t)(end - start));
        return r;
    }
    MkString *r = string_alloc((size_t)(end - start));
    if (end > start) memcpy(r->chars, s->chars + start, (size_t)(end - start) * sizeof(MkChar));
    return r;
}

char *mk_string_to_utf8(const MkString *s, size_t *out_length) {
    if (!mk_string_is_latin1(s)) return mk_utf16_to_utf8(s->chars, (size_t)s->length, out_length);
    size_t n = (size_t)s->length;
    const uint8_t *b = mk_string_bytes(s);
    char *out = malloc(n * 2 + 1);
    if (out == NULL) mk_panic("out of memory");
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        uint8_t c = b[i];
        if (c < 0x80) {
            out[o++] = (char)c;
        } else {
            out[o++] = (char)(0xC0 | (c >> 6));
            out[o++] = (char)(0x80 | (c & 0x3F));
        }
    }
    out[o] = '\0';
    if (out_length) *out_length = o;
    return out;
}

/* A null String that reaches here is a polluted value (heap pollution, see docs/memory-model.md): the dereference is a NullPointerException.
 * The compiler checks such values before (NullGuard), so this is the last line of defence, not the way a program is expected to fail. */
MkInt mk_string_length(const MkString *s) {
    if (s == NULL) mk_panic("NullPointerException");
    return s->length;
}

/* The number of ASCII bytes at the start of [p, end): eight at a time while the high bits are clear. */
static inline size_t ascii_prefix(const uint8_t *p, const uint8_t *end) {
    const uint8_t *q = p;
    while ((size_t)(end - q) >= 8) {
        uint64_t w;
        memcpy(&w, q, 8);
        if (w & 0x8080808080808080ULL) break;
        q += 8;
    }
    while (q < end && *q < 0x80) q++;
    return (size_t)(q - p);
}

MkString *mk_string_from_utf8(const char *utf8, size_t length) {
    static const uint8_t empty_utf8 = 0;
    const uint8_t *begin = utf8 ? (const uint8_t *)utf8 : &empty_utf8;
    const uint8_t *end = begin + (utf8 ? length : 0);
    size_t units = 0;
    uint32_t cp, widest = 0;
    /* source files and most data are mostly ASCII: runs of it are counted and copied in bulk, only the other characters are decoded */
    for (const uint8_t *p = begin; p < end;) {
        size_t run = ascii_prefix(p, end);
        p += run;
        units += run;
        if (p < end) {
            p += mk_utf8_decode(p, end, &cp);
            units += cp >= 0x10000 ? 2 : 1;
            if (cp > widest) widest = cp;
        }
    }
    if (widest < 256) {
        MkString *s = string_alloc8(units);
        uint8_t *out = (uint8_t *)(void *)s->chars;
        size_t i = 0;
        for (const uint8_t *p = begin; p < end;) {
            size_t run = ascii_prefix(p, end);
            if (run > 0) {
                memcpy(out + i, p, run);
                i += run;
                p += run;
            }
            if (p < end) {
                p += mk_utf8_decode(p, end, &cp);
                out[i++] = (uint8_t)cp;
            }
        }
        return s;
    }
    MkString *s = string_alloc(units);
    size_t i = 0;
    for (const uint8_t *p = begin; p < end;) {
        size_t run = ascii_prefix(p, end);
        for (size_t k = 0; k < run; k++) s->chars[i++] = (MkChar)p[k];
        p += run;
        if (p < end) {
            p += mk_utf8_decode(p, end, &cp);
            if (cp >= 0x10000) {
                cp -= 0x10000;
                s->chars[i++] = (MkChar)(0xD800 + (cp >> 10));
                s->chars[i++] = (MkChar)(0xDC00 + (cp & 0x3FF));
            } else {
                s->chars[i++] = (MkChar)cp;
            }
        }
    }
    return s;
}

static const uint8_t null_bytes[4] = {'n', 'u', 'l', 'l'};

/* Appends the characters of `p` (NULL is "null") at `out`, widening one-byte characters. */
static inline MkChar *put_wide(MkChar *out, const MkString *p) {
    if (p == NULL) {
        for (int i = 0; i < 4; i++) out[i] = null_bytes[i];
        return out + 4;
    }
    mk_string_copy_chars(p, 0, p->length, out);
    return out + p->length;
}

static MkString *concat_n(size_t n, const MkString *const *parts) {
    size_t total = 0;
    bool narrow = true;
    for (size_t i = 0; i < n; i++) {
        total += parts[i] ? (size_t)parts[i]->length : 4;
        if (parts[i] != NULL && !mk_string_is_latin1(parts[i])) narrow = false;
    }
    if (narrow) { /* every part one byte per character: so is the result */
        MkString *s = string_alloc8(total);
        uint8_t *out = (uint8_t *)(void *)s->chars;
        for (size_t i = 0; i < n; i++) {
            const uint8_t *c = parts[i] ? mk_string_bytes(parts[i]) : null_bytes;
            size_t len = parts[i] ? (size_t)parts[i]->length : 4;
            memcpy(out, c, len);
            out += len;
        }
        return s;
    }
    MkString *s = string_alloc(total);
    MkChar *out = s->chars;
    for (size_t i = 0; i < n; i++) out = put_wide(out, parts[i]);
    return s;
}

MkString *mk_string_concat(const MkString *left, const MkString *right) {
    /* NULL behaves like Kotlin's "null" in string templates. */
    const MkString *const p[2] = {left, right};
    return concat_n(2, p);
}

MkString *mk_string_concat3(const MkString *a, const MkString *b, const MkString *c) {
    const MkString *const p[3] = {a, b, c};
    return concat_n(3, p);
}
MkString *mk_string_concat4(const MkString *a, const MkString *b, const MkString *c, const MkString *d) {
    const MkString *const p[4] = {a, b, c, d};
    return concat_n(4, p);
}
MkString *mk_string_concat5(const MkString *a, const MkString *b, const MkString *c, const MkString *d, const MkString *e) {
    const MkString *const p[5] = {a, b, c, d, e};
    return concat_n(5, p);
}
MkString *mk_string_concat6(const MkString *a, const MkString *b, const MkString *c, const MkString *d, const MkString *e,
                            const MkString *f) {
    const MkString *const p[6] = {a, b, c, d, e, f};
    return concat_n(6, p);
}

static MkString *from_ascii(const char *text, size_t n) {
    MkString *s = string_alloc8(n);
    memcpy((uint8_t *)(void *)s->chars, text, n);
    return s;
}

size_t mk_itoa(int64_t value, char *buf) {
    char tmp[MK_ITOA_MAX];
    size_t i = MK_ITOA_MAX;
    uint64_t u = value < 0 ? 0u - (uint64_t)value : (uint64_t)value; /* exact for INT64_MIN */
    do {
        tmp[--i] = (char)('0' + (int)(u % 10u));
        u /= 10u;
    } while (u != 0u);
    size_t n = 0;
    if (value < 0) buf[n++] = '-';
    size_t digits = MK_ITOA_MAX - i;
    memcpy(buf + n, tmp + i, digits);
    return n + digits;
}

/* Decimal digits of an Int/Long straight into UTF-16 (two digits per division through a table): no temporary buffer.
 * mk_i64_length gives the characters of the text of `value` (sign included), mk_i64_write writes exactly that many. */
static const char digits2[201] =
    "00010203040506070809101112131415161718192021222324252627282930313233343536373839"
    "40414243444546474849505152535455565758596061626364656667686970717273747576777879"
    "8081828384858687888990919293949596979899";

size_t mk_i64_length(int64_t value) {
    static const uint64_t pow10[20] = {1ULL, 10ULL, 100ULL, 1000ULL, 10000ULL, 100000ULL, 1000000ULL, 10000000ULL, 100000000ULL, 1000000000ULL,
                                       10000000000ULL, 100000000000ULL, 1000000000000ULL, 10000000000000ULL, 100000000000000ULL,
                                       1000000000000000ULL, 10000000000000000ULL, 100000000000000000ULL, 1000000000000000000ULL,
                                       10000000000000000000ULL};
    uint64_t u = value < 0 ? 0u - (uint64_t)value : (uint64_t)value;
    /* the digit count from the bit length (log10(2) ~ 1233/4096), corrected by one table compare */
    uint64_t w = u | 1u; /* 0 has one digit; the powers of ten above 1 are even, so the compare is unchanged */
    unsigned bits = 64u - (unsigned)__builtin_clzll(w);
    unsigned t = (bits * 1233u) >> 12;
    size_t n = (size_t)t + (w >= pow10[t] ? 1u : 0u);
    return n + (value < 0 ? 1u : 0u);
}

void mk_i64_write(int64_t value, MkChar *out, size_t n) {
    uint64_t u = value < 0 ? 0u - (uint64_t)value : (uint64_t)value;
    MkChar *p = out + n;
    while (u >= 100u) {
        unsigned r = (unsigned)(u % 100u);
        u /= 100u;
        p -= 2;
        p[0] = (MkChar)(unsigned char)digits2[2 * r];
        p[1] = (MkChar)(unsigned char)digits2[2 * r + 1];
    }
    if (u >= 10u) {
        p -= 2;
        p[0] = (MkChar)(unsigned char)digits2[2 * u];
        p[1] = (MkChar)(unsigned char)digits2[2 * u + 1];
    } else {
        *--p = (MkChar)('0' + (unsigned)u);
    }
    if (value < 0) *--p = (MkChar)'-';
}

/* The same digits as bytes (a narrow StringBuilder buffer). */
void mk_i64_write8(int64_t value, uint8_t *out, size_t n) {
    uint64_t u = value < 0 ? 0u - (uint64_t)value : (uint64_t)value;
    uint8_t *p = out + n;
    while (u >= 100u) {
        unsigned r = (unsigned)(u % 100u);
        u /= 100u;
        p -= 2;
        p[0] = (uint8_t)digits2[2 * r];
        p[1] = (uint8_t)digits2[2 * r + 1];
    }
    if (u >= 10u) {
        p -= 2;
        p[0] = (uint8_t)digits2[2 * u];
        p[1] = (uint8_t)digits2[2 * u + 1];
    } else {
        *--p = (uint8_t)('0' + (unsigned)u);
    }
    if (value < 0) *--p = (uint8_t)'-';
}

MkString *mk_string_from_long(MkLong value) {
    size_t n = mk_i64_length(value);
    MkString *s = string_alloc8(n);
    mk_i64_write8(value, (uint8_t *)(void *)s->chars, n);
    return s;
}

MkString *mk_string_from_int(MkInt value) { return mk_string_from_long(value); }

/* `a + b + c ...` with Int / Long / Char operands among the strings, in one allocation and without a string per number: part i is the string s[i]
 * (kind 0; NULL is "null"), the decimal digits of v[i] (kind 1) or the character v[i] (kind 2); `mask` holds the kind of part i in bits 2i and 2i+1. */
static inline __attribute__((always_inline)) MkString *catm_n(unsigned mask, size_t n, const MkString *const *s, const int64_t *v) {
    size_t lens[6];
    size_t total = 0;
    bool narrow = true;
    for (size_t i = 0; i < n; i++) {
        unsigned k = (mask >> (2 * i)) & 3u;
        if (k == 0) {
            total += s[i] ? (size_t)s[i]->length : 4;
            if (s[i] != NULL && !mk_string_is_latin1(s[i])) narrow = false;
        } else if (k == 1) {
            lens[i] = mk_i64_length(v[i]);
            total += lens[i];
        } else {
            total += 1;
            if (v[i] > 0xFF || v[i] < 0) narrow = false;
        }
    }
    if (narrow) {
        MkString *r = string_alloc8(total);
        uint8_t *out = (uint8_t *)(void *)r->chars;
        for (size_t i = 0; i < n; i++) {
            unsigned k = (mask >> (2 * i)) & 3u;
            if (k == 0) {
                const uint8_t *c = s[i] ? mk_string_bytes(s[i]) : null_bytes;
                size_t len = s[i] ? (size_t)s[i]->length : 4;
                copy_short(out, c, len);
                out += len;
            } else if (k == 1) {
                size_t len = lens[i];
                mk_i64_write8(v[i], out, len);
                out += len;
            } else {
                *out++ = (uint8_t)v[i];
            }
        }
        return r;
    }
    MkString *r = string_alloc(total);
    MkChar *out = r->chars;
    for (size_t i = 0; i < n; i++) {
        unsigned k = (mask >> (2 * i)) & 3u;
        if (k == 0) {
            out = put_wide(out, s[i]);
        } else if (k == 1) {
            size_t len = lens[i];
            mk_i64_write(v[i], out, len);
            out += len;
        } else {
            *out++ = (MkChar)v[i];
        }
    }
    return r;
}

MkString *mk_string_concat_m3(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2) {
    const MkString *const s[3] = {s0, s1, s2};
    const int64_t v[3] = {v0, v1, v2};
    return catm_n((unsigned)mask, 3, s, v);
}
MkString *mk_string_concat_m4(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2,
                              const MkString *s3, MkLong v3) {
    const MkString *const s[4] = {s0, s1, s2, s3};
    const int64_t v[4] = {v0, v1, v2, v3};
    return catm_n((unsigned)mask, 4, s, v);
}
MkString *mk_string_concat_m5(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2,
                              const MkString *s3, MkLong v3, const MkString *s4, MkLong v4) {
    const MkString *const s[5] = {s0, s1, s2, s3, s4};
    const int64_t v[5] = {v0, v1, v2, v3, v4};
    return catm_n((unsigned)mask, 5, s, v);
}
MkString *mk_string_concat_m6(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2,
                              const MkString *s3, MkLong v3, const MkString *s4, MkLong v4, const MkString *s5, MkLong v5) {
    const MkString *const s[6] = {s0, s1, s2, s3, s4, s5};
    const int64_t v[6] = {v0, v1, v2, v3, v4, v5};
    return catm_n((unsigned)mask, 6, s, v);
}

MkString *mk_string_from_float(MkFloat value) {
    char buf[64];
    return from_ascii(buf, mk_format_float(value, buf, sizeof buf));
}

MkString *mk_string_from_byte(MkByte value) { return mk_string_from_int(value); }
MkString *mk_string_from_short(MkShort value) { return mk_string_from_int(value); }

MkString *mk_string_from_double(MkDouble value) {
    char buf[64];
    return from_ascii(buf, mk_format_double(value, buf, sizeof buf));
}

MkString *mk_string_from_boolean(MkBoolean value) {
    return value ? from_ascii("true", 4) : from_ascii("false", 5);
}

MkString *mk_string_from_char(MkChar value) {
    if (value < 256) {
        MkString *s = string_alloc8(1);
        ((uint8_t *)(void *)s->chars)[0] = (uint8_t)value;
        return s;
    }
    MkString *s = string_alloc(1);
    s->chars[0] = value;
    return s;
}

/* n Latin-1 bytes of x and y equal? Short ones with a few loads (n is the length of both). */
static inline bool latin1_equal(const uint8_t *x, const uint8_t *y, size_t n) {
    if (n >= 8 && n <= 16) {
        uint64_t p, q, r, t;
        memcpy(&p, x, 8); memcpy(&q, y, 8); memcpy(&r, x + n - 8, 8); memcpy(&t, y + n - 8, 8);
        return ((p ^ q) | (r ^ t)) == 0;
    }
    if (n >= 4 && n < 8) {
        uint32_t p, q, r, t;
        memcpy(&p, x, 4); memcpy(&q, y, 4); memcpy(&r, x + n - 4, 4); memcpy(&t, y + n - 4, 4);
        return ((p ^ q) | (r ^ t)) == 0;
    }
    if (n < 4) { /* 0..3: the first, the last and the middle byte cover them all */
        if (n == 0) return true;
        return ((x[0] ^ y[0]) | (x[n - 1] ^ y[n - 1]) | (x[n >> 1] ^ y[n >> 1])) == 0;
    }
    return memcmp(x, y, n) == 0;
}

bool mk_string_equals(const MkString *a, const MkString *b) {
    if (a == b) return true;
    if (a == NULL || b == NULL) return false;
    if (a->length != b->length) return false;
    size_t n = (size_t)a->length;
    bool la = mk_string_is_latin1(a), lb = mk_string_is_latin1(b);
    if (la && lb) return latin1_equal(mk_string_bytes(a), mk_string_bytes(b), n); /* two Latin-1 strings of one length */
    if (la == lb) return memcmp(a->chars, b->chars, n * sizeof(MkChar)) == 0;
    const MkString *w = la ? b : a, *l = la ? a : b; /* one of each: compare the characters */
    const uint8_t *lbytes = mk_string_bytes(l);
    for (size_t i = 0; i < n; i++) if (w->chars[i] != lbytes[i]) return false;
    return true;
}

/* The hash and the equality of the substring [from, to) of `s` without making it (the map lookups of `map[s.substring(a, b)]`, see
 * compiler/backend/src/opt/SubstrKey.kt): mk_string_hash_range(s, a, b) == mk_string_hash(substring(s, a, b)). The range was checked
 * where the substring would have been made; a bad one here is a bug of the caller. */
static void check_range(const MkString *s, MkInt from, MkInt to) {
    if (s == NULL) mk_panic("NullPointerException");
    if (from < 0 || to > s->length || from > to) mk_panic("StringIndexOutOfBoundsException: bad range");
}

MkInt mk_string_hash_range(const MkString *s, MkInt from, MkInt to) {
    check_range(s, from, to);
    uint32_t h = 0;
    if (mk_string_is_latin1(s)) {
        const uint8_t *b = mk_string_bytes(s);
        if (to - from <= 8) { /* a word: a plain loop (the unrolled and vectorized forms cost more to set up than they save) */
#if defined(__clang__)
#pragma clang loop unroll(disable) vectorize(disable)
#endif
            for (MkInt i = from; i < to; i++) h = h * 31u + b[i];
            return (MkInt)h;
        }
        for (MkInt i = from; i < to; i++) h = h * 31u + b[i];
    } else {
        for (MkInt i = from; i < to; i++) h = h * 31u + s->chars[i];
    }
    return (MkInt)h;
}

bool mk_string_equals_range(const MkString *key, const MkString *s, MkInt from, MkInt to) {
    if (key == NULL) return false; /* (the range was checked by mk_string_hash_range or by the caller: it must be valid) */
    size_t n = (size_t)(to - from);
    if ((size_t)key->length != n) return false;
    bool lk = mk_string_is_latin1(key), ls = mk_string_is_latin1(s);
    if (lk && ls) return latin1_equal(mk_string_bytes(key), mk_string_bytes(s) + from, n);
    for (size_t i = 0; i < n; i++) if (mk_string_at(key, (MkInt)i) != mk_string_at(s, from + (MkInt)i)) return false;
    return true;
}

MkInt mk_string_hash(const MkString *s) {
    uint32_t h = 0;
    if (s == NULL) mk_panic("NullPointerException");
    if (mk_string_is_latin1(s)) {
        const uint8_t *b = mk_string_bytes(s);
        for (MkInt i = 0; i < s->length; i++) h = h * 31u + b[i];
    } else {
        for (MkInt i = 0; i < s->length; i++) h = h * 31u + s->chars[i];
    }
    return (MkInt)h; /* two's-complement wraparound, like the JVM */
}
