#include "harness.h"

#include <stdint.h>
#include <stdlib.h>

int main(void) {
    unsigned char *bytes = mk_cinterop_calloc(4, 8);
    CHECK(bytes != NULL);
    for (size_t i = 0; i < 32; ++i) CHECK(bytes[i] == 0);
    free(bytes);

    CHECK(mk_cinterop_calloc(-1, 1) == NULL);
    CHECK(mk_cinterop_calloc(1, -1) == NULL);
    CHECK(mk_cinterop_calloc(INT64_MAX, INT64_MAX) == NULL);
    if ((uint64_t)SIZE_MAX < (uint64_t)INT64_MAX)
        CHECK(mk_cinterop_calloc((int64_t)SIZE_MAX + 1, 0) == NULL);
    void *zero = mk_cinterop_calloc(0, 0);
    free(zero);
    puts("cinterop calloc ABI and size checks OK");
    return 0;
}
