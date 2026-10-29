/*
 * libminirt — runtime ABI for Kotlin/Mini generated code.
 *
 * This header is the contract between the IR->C backend (compiler/) and the
 * runtime (runtime/minirt/). Generated C includes only this header. Changing a
 * declaration here is an ABI change: update both sides in the same commit.
 *
 * Naming: every exported symbol starts with `mk_` (functions) or `Mk` (types).
 */
#ifndef MINIRT_H
#define MINIRT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Kotlin primitive types ------------------------------------------------ */

typedef int8_t MkByte;
typedef int16_t MkShort;
typedef int32_t MkInt;
typedef int64_t MkLong;
typedef float MkFloat;
typedef double MkDouble;
typedef uint16_t MkChar; /* UTF-16 code unit, as in Kotlin */
typedef bool MkBoolean;

/* ---- Objects ---------------------------------------------------------------- */

typedef struct MkObject MkObject;
typedef struct MkTypeInfo MkTypeInfo;

/* Releases references held by the fields of `self`. It must not free `self`. */
typedef void (*MkDisposeFn)(MkObject *self);

struct MkTypeInfo {
    const char *name;          /* fully qualified Kotlin name, e.g. "kotlin.String" */
    size_t instance_size;      /* bytes, including the MkObject header */
    const MkTypeInfo *super;   /* NULL for kotlin.Any */
    MkDisposeFn dispose;       /* may be NULL when there are no reference fields */
    void *const *vtable;       /* virtual method table, may be NULL */
    size_t vtable_length;
    const MkTypeInfo *const *interfaces; /* implemented interfaces, may be NULL */
    size_t interface_count;
};

/* Every heap or stack object starts with this header. */
struct MkObject {
    const MkTypeInfo *type;
    uint32_t refcount; /* ignored when MK_FLAG_STATIC or MK_FLAG_STACK is set */
    uint32_t flags;
};

enum {
    MK_FLAG_STATIC = 1u << 0, /* static storage, e.g. string literals: never freed */
    MK_FLAG_STACK = 1u << 1,  /* lives on a C stack frame: never freed */
    MK_FLAG_SHARED = 1u << 2, /* reachable from several threads: atomic refcount */
};

/* Initialiser for an MkObject header in static storage. */
#define MK_STATIC_HEADER(type_info) {(type_info), 0u, MK_FLAG_STATIC}

/* Allocates a zeroed instance with refcount 1 from the default (Managed) allocator.
 * Aborts through mk_panic on out-of-memory. */
MkObject *mk_alloc(const MkTypeInfo *type);

/* Reference counting. Both accept NULL. Release frees the object (after calling
 * `dispose` along its type chain, most derived first) when the count reaches 0. */
void mk_retain(MkObject *object);
void mk_release(MkObject *object);

/* Marks an object (but not its fields) as shared between threads. */
void mk_share(MkObject *object);

/* Type tests used for `is` / `as`. Accept NULL (returns false). */
bool mk_is_instance(const MkObject *object, const MkTypeInfo *type);

/* Built-in type infos. */
extern const MkTypeInfo mk_type_Any;
extern const MkTypeInfo mk_type_String;

/* ---- Allocators (mini.memory) ----------------------------------------------- */

typedef struct MkAllocator MkAllocator;
struct MkAllocator {
    void *(*allocate)(MkAllocator *self, size_t size, size_t align); /* NULL on failure */
    void (*free)(MkAllocator *self, void *memory);                    /* may be NULL (Scoped) */
};

/* The allocator used by mk_alloc. Process-global, thread-safe. */
MkAllocator *mk_default_allocator(void);

/* Allocates a zeroed instance with refcount 1 from `allocator`. Returns NULL on failure. */
MkObject *mk_alloc_with(MkAllocator *allocator, const MkTypeInfo *type);

/* ---- kotlin.String ----------------------------------------------------------- */

/* Immutable UTF-16 string. `chars` is not NUL-terminated. */
typedef struct MkString {
    MkObject header;
    MkInt length;
    MkChar chars[];
} MkString;

/* Static string literal: MK_STRING_LITERAL(name, u"text") declares
 * `static const` storage named `name` with the header and the characters. */
#define MK_STRING_LITERAL(name, utf16_literal)                                         \
    static const struct {                                                              \
        MkObject header;                                                               \
        MkInt length;                                                                  \
        MkChar chars[sizeof(utf16_literal) / sizeof(MkChar)];                          \
    } name = {MK_STATIC_HEADER(&mk_type_String),                                       \
              (MkInt)(sizeof(utf16_literal) / sizeof(MkChar) - 1), utf16_literal}

MkString *mk_string_from_utf8(const char *utf8, size_t length); /* refcount 1 */
MkString *mk_string_concat(const MkString *left, const MkString *right);
MkString *mk_string_from_int(MkInt value);
MkString *mk_string_from_long(MkLong value);
MkString *mk_string_from_double(MkDouble value);
MkString *mk_string_from_boolean(MkBoolean value);
MkString *mk_string_from_char(MkChar value);
bool mk_string_equals(const MkString *left, const MkString *right); /* NULL-safe */
MkInt mk_string_hash(const MkString *string);                         /* Kotlin/Java hash */

/* ---- Console (kotlin.io) ------------------------------------------------------- */

void mk_print_string(const MkString *string); /* writes UTF-8 to stdout, no newline */
void mk_println_string(const MkString *string);
void mk_println_int(MkInt value);
void mk_println_long(MkLong value);
void mk_println_double(MkDouble value);
void mk_println_boolean(MkBoolean value);
void mk_println_char(MkChar value);
void mk_println_empty(void);

/* ---- Failure ------------------------------------------------------------------- */

/* Prints the message to stderr and aborts. Used until exceptions exist. */
_Noreturn void mk_panic(const char *message);

/* ---- Program entry ------------------------------------------------------------- */

/* Generated code defines `void mk_main(void)` for Kotlin `fun main()`.
 * The runtime provides C `main`, which initialises the runtime, calls mk_main,
 * flushes output and returns 0. */
void mk_main(void);

#ifdef __cplusplus
}
#endif

#endif
