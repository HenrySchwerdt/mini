/*
 * Minimal stand-in for libminirt, used only by the compiler tests.
 * It implements just the minirt.h functions the P0 backend emits calls to.
 * The real runtime lives in runtime/minirt/src and must behave the same for these.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "minirt.h"

const MkTypeInfo mk_type_Any = {"kotlin.Any", sizeof(MkObject), NULL, NULL, NULL, 0, NULL, 0};
const MkTypeInfo mk_type_String = {"kotlin.String", sizeof(MkString), &mk_type_Any, NULL, NULL, 0, NULL, 0};

_Noreturn void mk_panic(const char *message) {
    fflush(stdout);
    fprintf(stderr, "panic: %s\n", message);
    abort();
}

static MkString *new_string(MkInt length) {
    MkString *s = calloc(1, sizeof(MkString) + sizeof(MkChar) * (size_t)(length + 1));
    if (!s) mk_panic("out of memory");
    s->header.type = &mk_type_String;
    s->header.refcount = 1;
    s->length = length;
    return s;
}

MkString *mk_string_from_utf8(const char *utf8, size_t length) {
    MkString *s = new_string((MkInt)length); /* ASCII only in this stub */
    for (size_t i = 0; i < length; i++) s->chars[i] = (MkChar)(unsigned char)utf8[i];
    return s;
}

MkString *mk_string_concat(const MkString *l, const MkString *r) {
    MkString *s = new_string(l->length + r->length);
    memcpy(s->chars, l->chars, sizeof(MkChar) * (size_t)l->length);
    memcpy(s->chars + l->length, r->chars, sizeof(MkChar) * (size_t)r->length);
    return s;
}

static MkString *from_ascii(const char *text) { return mk_string_from_utf8(text, strlen(text)); }

MkString *mk_string_from_int(MkInt v) {
    char b[32];
    snprintf(b, sizeof b, "%d", v);
    return from_ascii(b);
}

MkString *mk_string_from_long(MkLong v) {
    char b[32];
    snprintf(b, sizeof b, "%lld", (long long)v);
    return from_ascii(b);
}

MkString *mk_string_from_boolean(MkBoolean v) { return from_ascii(v ? "true" : "false"); }

MkString *mk_string_from_char(MkChar v) {
    MkString *s = new_string(1);
    s->chars[0] = v;
    return s;
}

/* Kotlin Double.toString: shortest round-trip digits, "1.0E10" style outside [1e-3, 1e7). */
MkString *mk_string_from_double(MkDouble v) {
    if (v != v) return from_ascii("NaN");
    if (isinf(v)) return from_ascii(v > 0 ? "Infinity" : "-Infinity");
    if (v == 0) return from_ascii(signbit(v) ? "-0.0" : "0.0");
    char e[40];
    int prec;
    for (prec = 0; prec < 17; prec++) {
        snprintf(e, sizeof e, "%.*e", prec, v);
        if (strtod(e, NULL) == v) break;
    }
    /* e looks like [-]d[.ddd]e[+-]xx */
    char digits[24];
    int nd = 0, neg = 0;
    const char *p = e;
    if (*p == '-') { neg = 1; p++; }
    for (; *p && *p != 'e'; p++) if (*p != '.') digits[nd++] = *p;
    digits[nd] = 0;
    int exp10 = atoi(p + 1);
    char out[64];
    int n = 0;
    if (neg) out[n++] = '-';
    if (exp10 >= -3 && exp10 < 7) {
        if (exp10 >= 0) {
            for (int i = 0; i <= exp10; i++) out[n++] = i < nd ? digits[i] : '0';
            out[n++] = '.';
            if (nd > exp10 + 1) for (int i = exp10 + 1; i < nd; i++) out[n++] = digits[i];
            else out[n++] = '0';
        } else {
            out[n++] = '0';
            out[n++] = '.';
            for (int i = 0; i < -exp10 - 1; i++) out[n++] = '0';
            for (int i = 0; i < nd; i++) out[n++] = digits[i];
        }
    } else {
        out[n++] = digits[0];
        out[n++] = '.';
        if (nd > 1) for (int i = 1; i < nd; i++) out[n++] = digits[i];
        else out[n++] = '0';
        n += snprintf(out + n, sizeof out - (size_t)n, "E%d", exp10);
    }
    out[n] = 0;
    return from_ascii(out);
}

bool mk_string_equals(const MkString *a, const MkString *b) {
    if (a == b) return true;
    if (!a || !b || a->length != b->length) return false;
    return memcmp(a->chars, b->chars, sizeof(MkChar) * (size_t)a->length) == 0;
}

static void write_utf8(const MkString *s) {
    for (MkInt i = 0; i < s->length; i++) {
        unsigned c = s->chars[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < s->length && s->chars[i + 1] >= 0xDC00 && s->chars[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10) + (s->chars[++i] - 0xDC00u);
        }
        if (c < 0x80) putchar((int)c);
        else if (c < 0x800) { putchar(0xC0 | (int)(c >> 6)); putchar(0x80 | (int)(c & 0x3F)); }
        else if (c < 0x10000) { putchar(0xE0 | (int)(c >> 12)); putchar(0x80 | (int)((c >> 6) & 0x3F)); putchar(0x80 | (int)(c & 0x3F)); }
        else { putchar(0xF0 | (int)(c >> 18)); putchar(0x80 | (int)((c >> 12) & 0x3F)); putchar(0x80 | (int)((c >> 6) & 0x3F)); putchar(0x80 | (int)(c & 0x3F)); }
    }
}

void mk_print_string(const MkString *s) { write_utf8(s); }
void mk_println_string(const MkString *s) { write_utf8(s); putchar('\n'); }
void mk_println_empty(void) { putchar('\n'); }
void mk_println_int(MkInt v) { printf("%d\n", v); }
void mk_println_long(MkLong v) { printf("%lld\n", (long long)v); }
void mk_println_boolean(MkBoolean v) { puts(v ? "true" : "false"); }
void mk_println_char(MkChar v) { MkString *s = mk_string_from_char(v); mk_println_string(s); free(s); }
void mk_println_double(MkDouble v) { MkString *s = mk_string_from_double(v); mk_println_string(s); free(s); }

int main(void) {
    mk_main();
    fflush(stdout);
    return 0;
}
