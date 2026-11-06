/* kotlin.String: immutable UTF-16 strings. */
#include "internal.h"

#include <inttypes.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>

const MkTypeInfo mk_type_String = {
    "kotlin.String", sizeof(MkString), &mk_type_Any, NULL, NULL, 0, NULL, 0, NULL,
};

static MkString *string_alloc(size_t length) {
    if (length > (size_t)INT32_MAX) mk_panic("string too long");
    size_t size = sizeof(MkString) + length * sizeof(MkChar);
    MkObject *o = mk_alloc_sized_plain(&mk_type_String, size);
    if (o == NULL) mk_panic("out of memory");
    MkString *s = (MkString *)o;
    s->length = (MkInt)length;
    return s;
}

/* A Latin-1 string: one byte per character (the characters start at the same offset as the UTF-16 ones). */
static MkString *string_alloc8(size_t length) {
    if (length > (size_t)INT32_MAX) mk_panic("string too long");
    size_t size = offsetof(MkString, chars) + length;
    if (size < sizeof(MkObject)) size = sizeof(MkObject);
    MkObject *o = mk_alloc_sized_plain(&mk_type_String, size);
    if (o == NULL) mk_panic("out of memory");
    o->flags |= MK_FLAG_LATIN1;
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

MkInt mk_string_length(const MkString *s) { return s ? s->length : 0; }

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
    uint64_t u = value < 0 ? 0u - (uint64_t)value : (uint64_t)value;
    size_t n = 1;
    while (u >= 100u) {
        u /= 100u;
        n += 2;
    }
    if (u >= 10u) n++;
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

bool mk_string_equals(const MkString *a, const MkString *b) {
    if (a == b) return true;
    if (a == NULL || b == NULL) return false;
    if (a->length != b->length) return false;
    size_t n = (size_t)a->length;
    bool la = mk_string_is_latin1(a), lb = mk_string_is_latin1(b);
    if (la == lb) return memcmp(a->chars, b->chars, n * (la ? 1 : sizeof(MkChar))) == 0;
    const MkString *w = la ? b : a, *l = la ? a : b; /* one of each: compare the characters */
    const uint8_t *lbytes = mk_string_bytes(l);
    for (size_t i = 0; i < n; i++) if (w->chars[i] != lbytes[i]) return false;
    return true;
}

MkInt mk_string_hash(const MkString *s) {
    uint32_t h = 0;
    if (s != NULL) {
        if (mk_string_is_latin1(s)) {
            const uint8_t *b = mk_string_bytes(s);
            for (MkInt i = 0; i < s->length; i++) h = h * 31u + b[i];
        } else {
            for (MkInt i = 0; i < s->length; i++) h = h * 31u + s->chars[i];
        }
    }
    return (MkInt)h; /* two's-complement wraparound, like the JVM */
}
