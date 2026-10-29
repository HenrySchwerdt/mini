/* Links entry.c: the runtime's `main` must call mk_main and flush. Output is
 * compared with tests/expected/test_entry.out; run_tests.sh also checks the
 * panic path via test_panic.c. */
#include "minirt.h"

void mk_main(void) {
    mk_println_int(42);
    mk_println_boolean(true);
}
