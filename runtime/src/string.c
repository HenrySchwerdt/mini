/* kotlin.String: immutable UTF-16 strings. */
#include "internal.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

const MkTypeInfo mk_type_String = {
    "kotlin.String", sizeof(MkString), &mk_type_Any, NULL, NULL, 0, NULL, 0,
};

static MkString *string_alloc(size_t length) {
    if (length > (size_t)INT32_MAX) mk_panic("string too long");
    size_t size = sizeof(MkString) + length * sizeof(MkChar);
    MkObject *o = mk_alloc_sized(NULL, &mk_type_String, size);
    if (o == NULL) mk_panic("out of memory");
    MkString *s = (MkString *)o;
    s->length = (MkInt)length;
    return s;
}

MkString *mk_string_alloc(MkInt length) {
    if (length < 0) mk_panic("negative string length");
    return string_alloc((size_t)length);
}

MkInt mk_string_length(const MkString *s) { return s ? s->length : 0; }

MkString *mk_string_from_utf8(const char *utf8, size_t length) {
    const uint8_t *begin = (const uint8_t *)utf8;
    const uint8_t *end = begin + (utf8 ? length : 0);
    size_t units = 0;
    uint32_t cp;
    for (const uint8_t *p = begin; p < end;) {
        p += mk_utf8_decode(p, end, &cp);
        units += cp >= 0x10000 ? 2 : 1;
    }
    MkString *s = string_alloc(units);
    size_t i = 0;
    for (const uint8_t *p = begin; p < end;) {
        p += mk_utf8_decode(p, end, &cp);
        if (cp >= 0x10000) {
            cp -= 0x10000;
            s->chars[i++] = (MkChar)(0xD800 + (cp >> 10));
            s->chars[i++] = (MkChar)(0xDC00 + (cp & 0x3FF));
        } else {
            s->chars[i++] = (MkChar)cp;
        }
    }
    return s;
}

static const MkChar null_chars[4] = {'n', 'u', 'l', 'l'};

MkString *mk_string_concat(const MkString *left, const MkString *right) {
    /* NULL behaves like Kotlin's "null" in string templates. */
    const MkChar *lc = left ? left->chars : null_chars;
    const MkChar *rc = right ? right->chars : null_chars;
    size_t ln = left ? (size_t)left->length : 4;
    size_t rn = right ? (size_t)right->length : 4;
    MkString *s = string_alloc(ln + rn);
    memcpy(s->chars, lc, ln * sizeof(MkChar));
    memcpy(s->chars + ln, rc, rn * sizeof(MkChar));
    return s;
}

static MkString *from_ascii(const char *text, size_t n) {
    MkString *s = string_alloc(n);
    for (size_t i = 0; i < n; i++) s->chars[i] = (MkChar)(unsigned char)text[i];
    return s;
}

MkString *mk_string_from_int(MkInt value) {
    char buf[16];
    return from_ascii(buf, (size_t)snprintf(buf, sizeof buf, "%" PRId32, value));
}

MkString *mk_string_from_long(MkLong value) {
    char buf[32];
    return from_ascii(buf, (size_t)snprintf(buf, sizeof buf, "%" PRId64, value));
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
    MkString *s = string_alloc(1);
    s->chars[0] = value;
    return s;
}

bool mk_string_equals(const MkString *a, const MkString *b) {
    if (a == b) return true;
    if (a == NULL || b == NULL) return false;
    return a->length == b->length &&
           memcmp(a->chars, b->chars, (size_t)a->length * sizeof(MkChar)) == 0;
}

MkInt mk_string_hash(const MkString *s) {
    uint32_t h = 0;
    if (s != NULL)
        for (MkInt i = 0; i < s->length; i++) h = h * 31u + s->chars[i];
    return (MkInt)h; /* two's-complement wraparound, like the JVM */
}
