/* C interop helpers whose C ABI differs from Kotlin's fixed-width types. */
#include "minirt.h"

#include <stdlib.h>

void *mk_cinterop_calloc(int64_t count, int64_t size) {
    if (count < 0 || size < 0) return NULL;
    const uint64_t max = (uint64_t)SIZE_MAX;
    if ((uint64_t)count > max || (uint64_t)size > max) return NULL;
    if (size != 0 && (uint64_t)count > max / (uint64_t)size) return NULL;
    return calloc((size_t)count, (size_t)size);
}
