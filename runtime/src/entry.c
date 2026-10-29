/* Program entry. Kept separate so tests can link libminirt without `main`. */
#include "internal.h"

int main(int argc, char **argv) {
    mk_b1_set_args(argc, argv);
    mk_runtime_init();
    mk_main();
    mk_runtime_flush();
    return 0;
}
