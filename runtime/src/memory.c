#include "mini_runtime.h"
#include <stdlib.h>

void *mini_alloc(size_t size) {
    return malloc(size);
}

void *mini_realloc(void *ptr, size_t size) {
    return realloc(ptr, size);
}

void *mini_calloc(size_t count, size_t size) {
    return calloc(count, size);
}

void mini_free(void *ptr) {
    free(ptr);
}
