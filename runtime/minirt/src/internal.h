/* libminirt internal declarations. Not part of the ABI: generated code must
 * only include minirt.h. */
#ifndef MINIRT_INTERNAL_H
#define MINIRT_INTERNAL_H

#include "minirt.h"

#include <stdlib.h>

#if defined(_MSC_VER) && !defined(__clang__)
#include <intrin.h>
#endif

/* ---- Alignment ------------------------------------------------------------- */
/* MSVC in C mode has no max_align_t: use a union with the widest scalar types. */
typedef union MkMaxAlign {
    long long ll;
    long double ld;
    void *p;
    void (*fp)(void);
    double d;
} MkMaxAlign;

#if defined(_MSC_VER) && !defined(__clang__)
#define MK_MAX_ALIGN ((size_t)16)
#else
#define MK_MAX_ALIGN (sizeof(MkMaxAlign) > (size_t)16 ? sizeof(MkMaxAlign) : (size_t)16)
#endif

/* ---- Atomics on plain uint32_t (the MkObject layout is fixed) ------------- */
#if defined(_MSC_VER) && !defined(__clang__)
static inline uint32_t mk_atomic_fetch_add(uint32_t *p, uint32_t d) {
    return (uint32_t)_InterlockedExchangeAdd((volatile long *)p, (long)d);
}
static inline uint32_t mk_atomic_fetch_or(uint32_t *p, uint32_t v) {
    return (uint32_t)_InterlockedOr((volatile long *)p, (long)v);
}
#else
static inline uint32_t mk_atomic_fetch_add(uint32_t *p, uint32_t d) {
    return __atomic_fetch_add(p, d, __ATOMIC_ACQ_REL);
}
static inline uint32_t mk_atomic_fetch_or(uint32_t *p, uint32_t v) {
    return __atomic_fetch_or(p, v, __ATOMIC_ACQ_REL);
}
#endif

/* ---- Allocation ------------------------------------------------------------ */

/* Like mk_alloc_with, but with an explicit instance size (>= sizeof(MkObject)),
 * for variable-sized objects such as strings. NULL allocator = default. */
MkObject *mk_alloc_sized(MkAllocator *allocator, const MkTypeInfo *type, size_t size);

/* Frees the block of a heap object through its owning allocator (no dispose). */
void mk_free_object_memory(MkObject *object);

/* Allocates a string of `length` UTF-16 units from the default allocator
 * (panics on failure / too long). Characters are zero-initialised. */
MkString *mk_string_alloc(size_t length);

/* ---- UTF helpers ----------------------------------------------------------- */

/* Decodes one code point from [p, end) following the Unicode "maximal subpart"
 * rules; invalid input yields U+FFFD. Returns bytes consumed (>= 1). */
size_t mk_utf8_decode(const uint8_t *p, const uint8_t *end, uint32_t *code_point);

/* Encodes UTF-16 to a malloc'ed, NUL-terminated UTF-8 buffer; unpaired
 * surrogates become U+FFFD. *out_length (optional) excludes the terminator. */
char *mk_utf16_to_utf8(const MkChar *chars, size_t length, size_t *out_length);

/* ---- Runtime lifecycle ----------------------------------------------------- */

void mk_runtime_init(void);  /* idempotent; also done lazily by console output */
void mk_runtime_flush(void); /* flushes stdout */

/* Formats a double like Kotlin/JVM Double.toString into buf (capacity >= 48). */
size_t mk_format_double(MkDouble value, char *buf, size_t capacity);

#endif
