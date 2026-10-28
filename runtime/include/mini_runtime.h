#ifndef MINI_RUNTIME_H
#define MINI_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

void *mini_alloc(size_t size);
void *mini_realloc(void *ptr, size_t size);
void *mini_calloc(size_t count, size_t size);
void mini_free(void *ptr);

void mini_print_i32(int32_t value);

#endif