/* libminirt internal declarations. Not part of the ABI: generated code must
 * only include minirt.h. */
#ifndef MINIRT_INTERNAL_H
#define MINIRT_INTERNAL_H

#include "minirt.h"

#include <stdio.h>
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

/* Thread-local context handed from mk_alloc_sized / mk_free to allocators that
 * care (the DebugAllocator): the type being allocated and the call site. */
#if defined(_MSC_VER) && !defined(__clang__)
#define MK_TLS __declspec(thread)
#else
#define MK_TLS _Thread_local
#endif
typedef struct MkAllocCtx {
    const MkTypeInfo *type;
    const char *site;
} MkAllocCtx;
extern MK_TLS MkAllocCtx mk_tls_ctx;

/* Like mk_alloc_with, but with an explicit instance size (>= sizeof(MkObject)),
 * for variable-sized objects such as strings. NULL allocator = default. */
MkObject *mk_alloc_sized(MkAllocator *allocator, const MkTypeInfo *type, size_t size);
MkObject *mk_alloc_sized_site(MkAllocator *allocator, const MkTypeInfo *type, size_t size,
                              const char *site);

/* Frees the block of a heap object through its owning allocator (no dispose).
 * Marks the object MK_FLAG_FREED with refcount 0 first. */
void mk_free_object_memory(MkObject *object);

/* The prefix in front of every heap object (see alloc.c). */
#define MK_PREFIX_SIZE MK_MAX_ALIGN

/* ---- Statistics hooks (-DMK_STATS, see the D1 block of minirt.h; stats.c) --------------- */
#ifdef MK_STATS
void mk_stats_on_alloc(size_t bytes);
void mk_stats_on_free(size_t bytes);
void mk_stats_on_free_n(size_t objects, size_t bytes); /* an arena dies: all its objects at once */
int mk_arena_is(const MkAllocator *a);                  /* alloc_arena.c: is `a` an arena? */
void mk_arena_stat_alloc(MkAllocator *a, size_t bytes); /* an object of `bytes` (prefix included) was allocated in the arena */
void mk_stats_on_retain(int kind);  /* 0 = no-op (NULL / static / stack), 1 = plain, 2 = atomic */
void mk_stats_on_release(int kind);
#define MK_STAT_ALLOC(bytes) mk_stats_on_alloc(bytes)
#define MK_STAT_FREE(bytes) mk_stats_on_free(bytes)
#define MK_STAT_FREE_N(n, bytes) mk_stats_on_free_n((n), (bytes))
#define MK_STAT_RETAIN(kind) mk_stats_on_retain(kind)
#define MK_STAT_RELEASE(kind) mk_stats_on_release(kind)
#else
#define MK_STAT_ALLOC(bytes) ((void)0)
#define MK_STAT_FREE(bytes) ((void)0)
#define MK_STAT_FREE_N(n, bytes) ((void)0)
#define MK_STAT_RETAIN(kind) ((void)0)
#define MK_STAT_RELEASE(kind) ((void)0)
#endif

/* ---- ASan integration (arena poisons unused / reset memory) --------------- */
#if defined(__SANITIZE_ADDRESS__)
#define MK_HAS_ASAN 1
#elif defined(__has_feature)
#if __has_feature(address_sanitizer)
#define MK_HAS_ASAN 1
#endif
#endif
#ifdef MK_HAS_ASAN
#include <sanitizer/asan_interface.h>
#define MK_POISON(p, n) ASAN_POISON_MEMORY_REGION((p), (n))
#define MK_UNPOISON(p, n) ASAN_UNPOISON_MEMORY_REGION((p), (n))
#else
#define MK_POISON(p, n) ((void)(p), (void)(n))
#define MK_UNPOISON(p, n) ((void)(p), (void)(n))
#endif

/* ---- DebugAllocator hooks used by mk_free ----------------------------------- */
bool mk_debug_is_debug(const MkAllocator *allocator);
_Noreturn void mk_debug_double_free(MkAllocator *debug, void *block, const char *site);
size_t mk_debug_report_leaks_to(MkAllocator *debug, FILE *out);

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

/* Format like Kotlin/JVM Double.toString / Float.toString into buf (capacity >= 48). */
size_t mk_format_double(MkDouble value, char *buf, size_t capacity);
size_t mk_format_float(MkFloat value, char *buf, size_t capacity);

#endif
