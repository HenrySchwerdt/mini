/* kotlin.io console output and mk_panic. stdout is fully buffered. */
#include "internal.h"

#include <inttypes.h>
#include <stdio.h>

static bool initialised = false;

void mk_runtime_init(void) {
    if (initialised) return;
    initialised = true;
    setvbuf(stdout, NULL, _IOFBF, 1 << 16);
}

void mk_runtime_flush(void) { fflush(stdout); }

void mk_print_string(const MkString *s) {
    mk_runtime_init();
    if (s == NULL) {
        fwrite("null", 1, 4, stdout);
        return;
    }
    size_t len;
    char *utf8 = mk_utf16_to_utf8(s->chars, (size_t)s->length, &len);
    fwrite(utf8, 1, len, stdout);
    free(utf8);
}

void mk_println_empty(void) {
    mk_runtime_init();
    fputc('\n', stdout);
}

void mk_println_string(const MkString *s) {
    mk_print_string(s);
    mk_println_empty();
}

void mk_flush(void) { mk_runtime_flush(); }

/* Allocation-free printing: format into a stack buffer. */
static void put(const char *text, size_t n) {
    mk_runtime_init();
    fwrite(text, 1, n, stdout);
}

void mk_print_int(MkInt v) {
    char buf[16];
    put(buf, (size_t)snprintf(buf, sizeof buf, "%" PRId32, v));
}

void mk_print_long(MkLong v) {
    char buf[32];
    put(buf, (size_t)snprintf(buf, sizeof buf, "%" PRId64, v));
}

void mk_print_float(MkFloat v) {
    char buf[64];
    put(buf, mk_format_float(v, buf, sizeof buf));
}

void mk_print_double(MkDouble v) {
    char buf[64];
    put(buf, mk_format_double(v, buf, sizeof buf));
}

void mk_print_boolean(MkBoolean v) { put(v ? "true" : "false", v ? 4 : 5); }

void mk_print_char(MkChar c) {
    char buf[4];
    size_t n;
    if (c < 0x80) {
        buf[0] = (char)c;
        n = 1;
    } else if (c < 0x800) {
        buf[0] = (char)(0xC0 | (c >> 6));
        buf[1] = (char)(0x80 | (c & 0x3F));
        n = 2;
    } else if (c >= 0xD800 && c <= 0xDFFF) { /* lone surrogate */
        put("\xEF\xBF\xBD", 3);
        return;
    } else {
        buf[0] = (char)(0xE0 | (c >> 12));
        buf[1] = (char)(0x80 | ((c >> 6) & 0x3F));
        buf[2] = (char)(0x80 | (c & 0x3F));
        n = 3;
    }
    put(buf, n);
}

void mk_println_int(MkInt v) { mk_print_int(v); mk_println_empty(); }
void mk_println_long(MkLong v) { mk_print_long(v); mk_println_empty(); }
void mk_println_float(MkFloat v) { mk_print_float(v); mk_println_empty(); }
void mk_println_double(MkDouble v) { mk_print_double(v); mk_println_empty(); }
void mk_println_boolean(MkBoolean v) { mk_print_boolean(v); mk_println_empty(); }
void mk_println_char(MkChar v) { mk_print_char(v); mk_println_empty(); }

_Noreturn void mk_panic(const char *message) {
    fflush(stdout);
    fprintf(stderr, "fatal: %s\n", message ? message : "(null)");
    fflush(stderr);
    abort();
}

_Noreturn void mk_panic_string(const MkString *message) {
    if (message == NULL) mk_panic("null");
    char *utf8 = mk_utf16_to_utf8(message->chars, (size_t)message->length, NULL);
    mk_panic(utf8); /* aborts; the buffer is intentionally not freed */
}
