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
    return 0;
}
