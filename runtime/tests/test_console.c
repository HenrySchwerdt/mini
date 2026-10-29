/* Prints via the console API; run_tests.sh compares stdout with
 * tests/expected/test_console.out. */
#include "minirt.h"

MK_STRING_LITERAL(greeting, u"Hello, Wörld \U0001F600");

int main(void) {
    mk_print_string((const MkString *)&greeting);
    mk_println_empty();
    mk_println_string((const MkString *)&greeting);
    mk_println_string(NULL);
    mk_println_int(-2147483647 - 1);
    mk_println_long(-9223372036854775807LL - 1);
    mk_println_double(1.0);
    mk_println_double(1.0 / 3);
    mk_println_double(1e7);
    mk_println_double(-0.0);
    mk_println_boolean(true);
    mk_println_boolean(false);
    mk_println_char('x');
    mk_println_char(0xE4);
    mk_println_char(0xD83D); /* lone surrogate -> U+FFFD */
    mk_println_empty();

    /* allocation-free printing, no newline */
    mk_print_int(-7);
    mk_print_char(' ');
    mk_print_long(1234567890123LL);
    mk_print_char(' ');
    mk_print_float(0.1f);
    mk_print_char(' ');
    mk_print_float(1.0e10f);
    mk_print_char(' ');
    mk_print_double(0.1);
    mk_print_char(' ');
    mk_print_boolean(true);
    mk_print_char(' ');
    mk_print_boolean(false);
    mk_print_char(' ');
    mk_print_char(0x20AC); /* 3-byte UTF-8 */
    mk_print_char(0xD83D); /* lone surrogate -> U+FFFD */
    mk_println_empty();
    mk_println_float(-0.0f);
    mk_println_float(3.4028235e38f);
    mk_flush();
    MkString *s = mk_string_alloc(2);
    s->chars[0] = 'o';
    s->chars[1] = 'k';
    mk_println_int(mk_string_length(s));
    mk_println_int(mk_string_length(NULL));
    mk_println_string(s);
    mk_release(&s->header);
    return 0;
}
