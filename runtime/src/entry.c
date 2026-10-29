/* Program entry. Kept separate so tests can link libminirt without `main`. */
#include "internal.h"

int main(int argc, char **argv) {
    (void)argc;
    (void)argv;
    mk_runtime_init();
    mk_main();
    mk_runtime_flush();
    return 0;
}
