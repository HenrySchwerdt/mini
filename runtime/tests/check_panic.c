/* Not a test_*.c file (it aborts by design). run_tests.sh checks that stdout
 * "before" is flushed first, stderr is "fatal: boom" and the exit is abnormal. */
#include "minirt.h"

void mk_main(void) {
    mk_println_string(NULL == (void *)0 ? (const MkString *)0 : (const MkString *)0);
    mk_panic("boom");
}
