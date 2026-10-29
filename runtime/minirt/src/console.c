/* kotlin.io console output and mk_panic. stdout is fully buffered. */
#include "internal.h"

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

static void println_owned(MkString *s) {
    mk_println_string(s);
    mk_release(&s->header);
}

void mk_println_int(MkInt v) { println_owned(mk_string_from_int(v)); }
void mk_println_long(MkLong v) { println_owned(mk_string_from_long(v)); }
void mk_println_double(MkDouble v) { println_owned(mk_string_from_double(v)); }
void mk_println_boolean(MkBoolean v) { println_owned(mk_string_from_boolean(v)); }
void mk_println_char(MkChar v) { println_owned(mk_string_from_char(v)); }

_Noreturn void mk_panic(const char *message) {
    fflush(stdout);
    fprintf(stderr, "fatal: %s\n", message ? message : "(null)");
    fflush(stderr);
    abort();
}
