/*
 * libminirt — runtime ABI for Kotlin/Mini generated code.
 *
 * This header is the contract between the IR->C backend (compiler/) and the
 * runtime (runtime/). Generated C includes only this header. Changing a
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
    MK_FLAG_FREED = 1u << 3,  /* set by the runtime when the memory is returned; lets
                                 mk_free / mk_release diagnose double frees. Never set
                                 it from generated code. */
};

/* Initialiser for an MkObject header in static storage. */
#define MK_STATIC_HEADER(type_info) {(type_info), 0u, MK_FLAG_STATIC}

/* Allocates a zeroed instance with refcount 1 from the default (Managed) allocator
 * (see mk_default_allocator). Aborts through mk_panic on out-of-memory. */
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

/* Capability traits of an allocator (plan section 4.1). The compiler derives its
 * checks from them; the runtime uses them to decide what mk_free may do. */
enum {
    MK_ALLOCATOR_DEALLOCATING = 1u << 0, /* objects are freed one by one (mk_free / release) */
    MK_ALLOCATOR_SCOPED = 1u << 1,       /* everything dies together; no individual free */
    MK_ALLOCATOR_MANAGED = 1u << 2,      /* reference counted; releasing the last ref frees */
    MK_ALLOCATOR_THREAD_SAFE = 1u << 3,  /* may be used from several threads */
};

typedef struct MkAllocator MkAllocator;
struct MkAllocator {
    /* Returns a block of at least `size` bytes aligned to `align` (a power of two,
     * up to the maximal fundamental alignment, 16 on common targets, is always supported), or NULL on failure. Contents unspecified. */
    void *(*allocate)(MkAllocator *self, size_t size, size_t align);
    /* Returns a block obtained from `allocate`. May be NULL (Scoped allocators). */
    void (*free)(MkAllocator *self, void *memory);
    /* Bit set of MK_ALLOCATOR_*. ABI note: this field was appended after the first
     * release of the struct. Zero-initialised / positionally initialised legacy
     * allocators (`{allocate, free}`) have traits == 0, which mk_allocator_traits
     * reports as DEALLOCATING when `free != NULL` and SCOPED otherwise. Always read
     * traits through mk_allocator_traits. */
    uint32_t traits;
};

/* Trait bits of `allocator` (NULL = the default allocator), with the legacy
 * fallback described above. */
uint32_t mk_allocator_traits(MkAllocator *allocator);

/* Hidden allocator prefix. mk_alloc_with does NOT hand `type->instance_size` bytes
 * to `allocate`: every heap object lives in a block laid out as
 *
 *   block (what allocate returned)   object (the MkObject* seen by Kotlin code)
 *   |<-- max alignment (16) -->|
 *   [ MkAllocator *owner | padding  ][ MkObject header | fields ... ]
 *
 * so a custom allocator is asked for `mk_object_block_size(type)` bytes
 * (instance_size + prefix) with maximal alignment, the whole block is zeroed, and
 * `free` later receives the BLOCK pointer, not the object pointer. Static and stack
 * objects have no prefix and never reach `free`. */
size_t mk_object_block_size(const MkTypeInfo *type);

/* The allocator used by mk_alloc: reference counted, process-global, thread-safe.
 * Traits: MANAGED | THREAD_SAFE. It is backed by malloc/free, but unlike the Heap
 * allocator below the compiler may assume RC semantics (objects are released, not
 * freed by hand) for objects from it. */
MkAllocator *mk_default_allocator(void);
/* The same instance under its plan-section-4 name (Rc : Managed, ThreadSafe). */
MkAllocator *mk_rc_allocator(void);
/* Heap : Deallocating, ThreadSafe. malloc/free; objects are owned manually and
 * must be freed exactly once with mk_free (or by their refcount reaching 0). */
MkAllocator *mk_heap_allocator(void);

/* Allocates a zeroed instance with refcount 1 from `allocator` (NULL = default).
 * Returns NULL on failure, if `type` is NULL, or if type->instance_size is smaller
 * than sizeof(MkObject) (such "types" are interfaces and cannot be instantiated). */
MkObject *mk_alloc_with(MkAllocator *allocator, const MkTypeInfo *type);

/* Like mk_alloc_with, additionally telling the allocator where the allocation
 * happens (e.g. "src/Foo.kt:12:5"). Only the DebugAllocator uses the site; every
 * other allocator ignores it. The string is copied. */
MkObject *mk_alloc_with_site(MkAllocator *allocator, const MkTypeInfo *type, const char *site);

/* Explicit free for manual memory management. Runs the dispose chain (most derived
 * first) and returns the memory to the owning allocator REGARDLESS of the refcount:
 * manual-mode objects have refcount 1 and are never retained by generated code, so
 * mk_free is their equivalent of the final mk_release. Accepts NULL (no-op).
 * Panics if the object is static or stack-allocated, was already freed (reliable
 * with Pool and DebugAllocator memory, best effort otherwise: ASan reports the rest)
 * or belongs to a SCOPED allocator (arena objects cannot be freed individually).
 * Do not mix: after mk_free any remaining reference is dangling. */
void mk_free(MkObject *object);
/* mk_free that reports `site` as the place of the free to the DebugAllocator. */
void mk_free_with_site(MkObject *object, const char *site);

/* -- Arena (Scoped, not thread-safe) --
 * Bump allocation in chunks that grow geometrically; oversized requests get a
 * dedicated chunk. Alignment up to any power of two is honoured. `initial` is the
 * first chunk size in bytes (0 = 4096). Returns NULL on out-of-memory.
 * Dispose policy: the arena NEVER runs `dispose` for objects still alive in it,
 * neither in reset nor in destroy. It does not track objects (zero per-allocation
 * overhead) and the compiler's escape analysis guarantees nothing outlives the
 * scope; arena objects must therefore not own reference-counted fields that need
 * releasing, or the code must release them explicitly. Releasing an arena object
 * through mk_release runs dispose but not free. */
MkAllocator *mk_arena_create(size_t initial);
/* Invalidates every object allocated so far but keeps regular chunks for reuse and
 * frees dedicated oversized chunks. */
void mk_arena_reset(MkAllocator *arena);
/* Frees all chunks. All objects of the arena become invalid. */
void mk_arena_destroy(MkAllocator *arena);
size_t mk_arena_used(const MkAllocator *arena);     /* bytes handed out since the last reset */
size_t mk_arena_reserved(const MkAllocator *arena); /* bytes held in chunks */

/* -- Pool (Deallocating, not thread-safe) --
 * Free-list allocator for blocks of one fixed size, carved from slabs of
 * `blocks_per_slab` blocks (0 = 64). Requests larger than `block_size`, or with an
 * alignment above the maximal fundamental one, fail with NULL. For objects of one type use
 * block_size = mk_object_block_size(type). `block_size` is rounded up to the maximal alignment. */
MkAllocator *mk_pool_create(size_t block_size, size_t blocks_per_slab);
void mk_pool_destroy(MkAllocator *pool); /* frees all slabs, live blocks included */
size_t mk_pool_live_count(const MkAllocator *pool);

/* -- DebugAllocator --
 * Wraps `inner` (NULL = heap) and records every live allocation with the site given
 * through mk_alloc_with_site (or "<unknown>"), counts allocations and frees, and
 * diagnoses misuse: freeing a block twice or freeing a pointer it never returned
 * panics with the allocation site (and the earlier free site). Freed blocks are
 * quarantined (the last few are kept unreleased) so double frees stay detectable.
 * Its traits are the inner allocator's; it is thread-safe when the inner one is
 * (internally locked). For a SCOPED inner allocator, releases only update the books.
 * Intended for a `--debug-memory` build mode. */
MkAllocator *mk_debug_allocator_create(MkAllocator *inner);
/* Releases the books, the quarantine and, through `inner`, every block that is
 * still live. It does not run dispose. Does not destroy `inner`. */
void mk_debug_allocator_destroy(MkAllocator *debug);

typedef struct MkDebugStats {
    size_t allocations; /* total successful allocations */
    size_t frees;       /* total frees */
    size_t live;        /* allocations - frees */
    size_t live_bytes;  /* block bytes of live allocations (prefix included) */
} MkDebugStats;
void mk_debug_stats(MkAllocator *debug, MkDebugStats *out);

/* Prints `leak: <TypeName> allocated at <site>` to stderr for every live allocation
 * (in allocation order) and returns how many there are. */
size_t mk_debug_report_leaks(MkAllocator *debug);

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
/* A string of `length` UTF-16 units (refcount 1) whose characters the caller must
 * fill in: their contents are unspecified. Panics when length < 0 or on
 * out-of-memory. */
MkString *mk_string_alloc(MkInt length);
MkInt mk_string_length(const MkString *string); /* 0 for NULL */
MkString *mk_string_concat(const MkString *left, const MkString *right);
MkString *mk_string_from_int(MkInt value);
MkString *mk_string_from_long(MkLong value);
MkString *mk_string_from_float(MkFloat value); /* Kotlin/JVM Float.toString */
MkString *mk_string_from_double(MkDouble value);
MkString *mk_string_from_byte(MkByte value);
MkString *mk_string_from_short(MkShort value);
MkString *mk_string_from_boolean(MkBoolean value);
MkString *mk_string_from_char(MkChar value);
bool mk_string_equals(const MkString *left, const MkString *right); /* NULL-safe */
MkInt mk_string_hash(const MkString *string);                         /* Kotlin/Java hash */

/* ---- Console (kotlin.io) ------------------------------------------------------- */

/* stdout is fully buffered. A NULL string prints as "null" (Kotlin String?). */
void mk_print_string(const MkString *string); /* writes UTF-8 to stdout, no newline */
/* Allocation-free printing without newline, so `print(x)` needs no temporaries.
 * Output is identical to the corresponding mk_string_from_* text. */
void mk_print_int(MkInt value);
void mk_print_long(MkLong value);
void mk_print_float(MkFloat value);
void mk_print_double(MkDouble value);
void mk_print_boolean(MkBoolean value);
void mk_print_char(MkChar value);
void mk_flush(void); /* flushes stdout; mk_panic and the entry point do it too */
void mk_println_string(const MkString *string);
void mk_println_int(MkInt value);
void mk_println_long(MkLong value);
void mk_println_float(MkFloat value);
void mk_println_double(MkDouble value);
void mk_println_boolean(MkBoolean value);
void mk_println_char(MkChar value);
void mk_println_empty(void);

/* ---- Failure ------------------------------------------------------------------- */

/* Prints the message to stderr and aborts. Used until exceptions exist. */
_Noreturn void mk_panic(const char *message);
/* Same with a Kotlin string message (NULL prints "null"). */
_Noreturn void mk_panic_string(const MkString *message);

/* ---- P1-A: classes ------------------------------------------------------------ */

/* Class model used by generated code.
 *
 * Virtual dispatch: `MkTypeInfo.vtable` of every compiler-generated class starts with
 * three fixed slots (the kotlin.Any members), followed by one slot per user-declared
 * open/abstract/override member in declaration order (superclass slots first).
 * Slot functions take the receiver as first (MkObject *) argument. Reference results
 * are owned (+1); reference arguments are borrowed.
 *
 * Interface dispatch (per-class itable lookup by interface type info): the class'
 * `interfaces` array lists ALL interfaces the class implements, transitively and
 * including those of its superclasses. The vtable holds, after the class slots, one
 * pointer per entry of `interfaces` (same order), each pointing to that interface's
 * function table for this class (an array of function pointers indexed by the method
 * index the interface declares). `vtable_length` counts class slots + interface
 * entries, so the interface block starts at vtable[vtable_length - interface_count]. */
enum {
    MK_VSLOT_TO_STRING = 0,
    MK_VSLOT_EQUALS = 1,
    MK_VSLOT_HASH_CODE = 2,
    MK_VSLOT_FIRST_USER = 3,
};

/* Default kotlin.Any members. toString gives "Name@<hex identity hash>". */
/* Returns const like generated toString overrides, so both fit the same vtable slot type. */
const MkString *mk_any_to_string(MkObject *self); /* refcount 1 */
bool mk_any_equals(MkObject *self, MkObject *other);
MkInt mk_any_hash_code(MkObject *self);

/* Null-safe virtual versions (also correct for strings and boxed primitives).
 * mk_to_string(NULL) is "null" and returns an owned string; mk_hash_code(NULL) is 0;
 * mk_equals(NULL, NULL) is true. */
MkString *mk_to_string(MkObject *object);
bool mk_equals(MkObject *left, MkObject *right);
MkInt mk_hash_code(MkObject *object);

/* Function table of `object`'s class for `iface`, or NULL if it does not implement it. */
void *const *mk_itable(const MkObject *object, const MkTypeInfo *iface);

/* Failures with Kotlin exception names (until exceptions exist these abort). */
_Noreturn void mk_throw_npe(void);
_Noreturn void mk_throw_class_cast(const MkObject *object, const MkTypeInfo *target);

/* `as`: returns `object` (NULL passes) or aborts with ClassCastException. */
MkObject *mk_cast(MkObject *object, const MkTypeInfo *target);
/* `!!`: returns `object` or aborts with NullPointerException. */
MkObject *mk_not_null(MkObject *object);

/* Boxed primitives (representation of Int?, Long?, Boolean?, Double?, Char?, and of
 * primitives stored as Any). Boxes are ordinary reference-counted objects. */
extern const MkTypeInfo mk_type_Int;
extern const MkTypeInfo mk_type_Long;
extern const MkTypeInfo mk_type_Boolean;
extern const MkTypeInfo mk_type_Double;
extern const MkTypeInfo mk_type_Char;
MkObject *mk_box_int(MkInt value); /* refcount 1 */
MkObject *mk_box_long(MkLong value);
MkObject *mk_box_boolean(MkBoolean value);
MkObject *mk_box_double(MkDouble value);
MkObject *mk_box_char(MkChar value);
/* Unboxing aborts with NullPointerException on NULL. */
MkInt mk_unbox_int(const MkObject *box);
MkLong mk_unbox_long(const MkObject *box);
MkBoolean mk_unbox_boolean(const MkObject *box);
MkDouble mk_unbox_double(const MkObject *box);
MkChar mk_unbox_char(const MkObject *box);

/* ---- end P1-A ------------------------------------------------------------------- */

/* ---- Program entry ------------------------------------------------------------- */

/* Generated code defines `void mk_main(void)` for Kotlin `fun main()`.
 * The runtime provides C `main`, which initialises the runtime, calls mk_main,
 * flushes output and returns 0. */
void mk_main(void);

/* ---- v1-S: stdlib primitives (stdlib/mini/internal/Intrinsics.kt) --------- */
/* Arguments are borrowed; reference results are owned (+1). Out-of-range indices abort via mk_panic. */

MkString *mk_type_name(MkObject *object);  /* MkTypeInfo.name, e.g. "kotlin.Int" */
MkInt mk_identity_hash(MkObject *object);

MkChar mk_string_char_at(const MkString *s, MkInt index);
MkInt mk_string_compare(const MkString *a, const MkString *b); /* java.lang.String.compareTo */
MkString *mk_string_substring(const MkString *s, MkInt start, MkInt end);
MkBoolean mk_string_is_double(const MkString *s); /* Java Double.parseDouble syntax (decimal), trimmed */
MkDouble mk_string_parse_double(const MkString *s);

/* Growable UTF-16 buffer behind kotlin.text.StringBuilder (an opaque heap object). */
MkObject *mk_sb_new(void);
void mk_sb_append_string(MkObject *sb, const MkString *s);
void mk_sb_append_char(MkObject *sb, MkChar c);
void mk_sb_append_int(MkObject *sb, MkInt value);   /* decimal digits, written straight into the buffer */
void mk_sb_append_long(MkObject *sb, MkLong value);
MkInt mk_sb_length(MkObject *sb);
MkChar mk_sb_char_at(MkObject *sb, MkInt index);
void mk_sb_set_char_at(MkObject *sb, MkInt index, MkChar c);
void mk_sb_set_length(MkObject *sb, MkInt length); /* truncates, or pads with NUL characters */
MkString *mk_sb_to_string(MkObject *sb);

/* Fixed-size array of retained `Any?` slots (storage of ArrayList, HashMap, ...). */
MkObject *mk_ref_array_new(MkInt size); /* all slots null */
MkObject *mk_ref_array_get(MkObject *array, MkInt index); /* returns +1 (or NULL) */
void mk_ref_array_set(MkObject *array, MkInt index, MkObject *value); /* retains value, releases the old one */
MkInt mk_ref_array_length(MkObject *array);
MkObject *mk_ref_array_get_b(MkObject *array, MkInt index); /* D2: borrowed, no retain (valid while the array keeps the slot) */
void mk_ref_array_set_o(MkObject *array, MkInt index, MkObject *value); /* D2: takes over the caller's reference to value */

/* Failures of the stdlib (until exceptions exist): print "fatal: <Kind>: <message>" to stderr and abort. */
_Noreturn void mk_throw_illegal_argument(const MkString *message);
_Noreturn void mk_throw_illegal_state(const MkString *message);
_Noreturn void mk_throw_index_out_of_bounds(const MkString *message);
_Noreturn void mk_throw_no_such_element(const MkString *message);
_Noreturn void mk_throw_number_format(const MkString *message);
_Noreturn void mk_throw_unsupported_operation(const MkString *message);
_Noreturn void mk_throw_not_implemented(const MkString *message);

/* ---- end v1-S ------------------------------------------------------------------- */

/* ---- v1-L ---------------------------------------------------------------------- */

/* Exceptions, arrays and a few helpers for the v1-L language features (lambdas, generics,
 * exceptions). Implemented in src/v1l.c.
 *
 * Exception ABI (error-return, plan section 5): a function that can throw reports it by
 * leaving a non-NULL owned reference in the thread-local `mk_pending_exception` and returning a
 * dummy value (0 / NULL). Every caller checks `mk_pending_exception != NULL` right after the call
 * and either jumps to its `catch` handler or releases its locals and returns the same way. The
 * handler takes the exception with mk_catch(). The entry point (generated `mk_main` wrapper)
 * reports a still pending exception with mk_report_uncaught() and exits with status 1. */

#if defined(_MSC_VER) && !defined(__clang__)
#define MK_THREAD_LOCAL __declspec(thread)
#else
#define MK_THREAD_LOCAL _Thread_local
#endif

extern MK_THREAD_LOCAL MkObject *mk_pending_exception;

/* The address of this thread's `mk_pending_exception`. For generated code that cannot name the thread-local variable: the in-process
 * JIT (the ORC loader resolves no TLS relocations of generated code, `LlvmTarget.pendingAccessor`); optimizers may treat the call as
 * pure, so it costs one call per function that tests for exceptions. Ordinary builds reference the variable directly. */
MkObject **mk_pending_slot(void);

/* `throw e`: retains `e`, makes it the pending exception (a previously pending one is released).
 * `e == NULL` throws NullPointerException. The caller then runs its exceptional exit path. */
void mk_throw(MkObject *exception);
/* Makes `exception` pending, transferring the caller's reference (rethrow after a handler). */
void mk_rethrow(MkObject *exception);
/* Takes the pending exception: returns it (owned by the caller) and clears the pending state. */
MkObject *mk_catch(void);
/* Prints `Exception in thread "main" <toString>` to stderr (after flushing stdout) and exits
 * with status 1. Called with an exception pending. */
_Noreturn void mk_report_uncaught(void);

/* java.lang.Throwable layout; user exception classes embed it as their first member. */
typedef struct MkThrowable {
    MkObject header;
    const MkString *message; /* may be NULL */
    MkObject *cause;         /* may be NULL */
} MkThrowable;

/* Built-in exception classes. Every one has the MkThrowable layout; `struct <Name>` wraps it so that
 * generated subclasses can embed their superclass as the first member. vtable: toString, equals,
 * hashCode, then slot 3 = `message` getter (`const MkString *(MkObject *)`, owned result). Type
 * infos are named `<struct>_ti`, constructors `<struct>_new`. */
#define MK_EXCEPTION_SUBCLASSES(X)                                                       \
    X(MkException) X(MkRuntimeException) X(MkError)                                      \
    X(MkIllegalStateException) X(MkIllegalArgumentException) X(MkNullPointerException)  \
    X(MkArithmeticException) X(MkClassCastException) X(MkIndexOutOfBoundsException)     \
    X(MkArrayIndexOutOfBoundsException) X(MkStringIndexOutOfBoundsException)           \
    X(MkUnsupportedOperationException)                                                   \
    X(MkNoSuchElementException) X(MkNumberFormatException)                              \
    X(MkUninitializedPropertyAccessException) X(MkNotImplementedError)                   \
    X(MkNegativeArraySizeException) X(MkAssertionError)
#define MK_DECLARE_EXCEPTION(Name)                                                       \
    extern const MkTypeInfo Name##_ti;                                                   \
    /* allocates an instance (refcount 1); retains message and cause */                  \
    MkObject *Name##_new(const MkString *message, MkObject *cause);
#define MK_DEFINE_EXCEPTION_STRUCT(Name) struct Name { MkThrowable base; };
extern const MkTypeInfo MkThrowable_ti;
MkObject *MkThrowable_new(const MkString *message, MkObject *cause);
MK_EXCEPTION_SUBCLASSES(MK_DECLARE_EXCEPTION)
MK_EXCEPTION_SUBCLASSES(MK_DEFINE_EXCEPTION_STRUCT)

/* Initialiser used by constructors of user classes that extend a built-in exception. */
void mk_throwable_init(MkObject *self, const MkString *message, MkObject *cause);
/* `Throwable(cause)`: message is cause.toString() (NULL cause gives NULL). Owned result. */
const MkString *mk_throwable_cause_message(MkObject *cause);
const MkString *mk_throwable_message(MkObject *self); /* the slot-3 default, owned result */
const MkString *mk_throwable_to_string(MkObject *self); /* the slot-0 default of every Throwable */
MkObject *mk_throwable_cause(MkObject *self);         /* owned result (may be NULL) */
void mk_throwable_print_stack_trace(MkObject *self);  /* toString to stderr */

/* Raising helpers for runtime-detected failures: they make the exception pending and return
 * (generated code checks afterwards, so callers return a dummy value). */
void mk_raise(const MkTypeInfo *type, const char *message); /* message may be NULL */
void mk_raise_npe(void);
void mk_raise_class_cast(const MkObject *object, const MkTypeInfo *target);
void mk_raise_arith(const char *message);
void mk_raise_index(MkInt index, MkInt length);
void mk_raise_illegal_argument(const char *message);
void mk_raise_illegal_state(const char *message);
void mk_raise_uninitialized(const char *property);

/* Exception-raising variants of the P1-A checks (those abort instead). */
MkObject *mk_checked_cast(MkObject *object, const MkTypeInfo *target); /* NULL passes */
MkObject *mk_checked_not_null(MkObject *object);
MkInt mk_checked_unbox_int(const MkObject *box);
MkLong mk_checked_unbox_long(const MkObject *box);
MkBoolean mk_checked_unbox_boolean(const MkObject *box);
MkDouble mk_checked_unbox_double(const MkObject *box);
MkChar mk_checked_unbox_char(const MkObject *box);

/* `kotlin.Any()`: a plain instance (refcount 1). */
MkObject *mk_any_new(void);

/* Comparable.compareTo for boxed primitives and strings; for any other object the function
 * table of `comparable` (the Comparable interface type info, may be NULL) is used, method 0. */
MkInt mk_compare_to(MkObject *left, MkObject *right, const MkTypeInfo *comparable);

/* Arrays. `Array<T>` holds references (released by dispose); IntArray etc. hold primitives.
 * Constructors raise NegativeArraySizeException for a negative size. Element access raises
 * ArrayIndexOutOfBoundsException. get_obj returns an owned reference. */
typedef struct MkArray {
    MkObject header;
    MkInt length;
    /* elements follow, 8-byte aligned */
} MkArray;
extern const MkTypeInfo MkObjectArray_ti, MkIntArray_ti, MkLongArray_ti, MkDoubleArray_ti, MkBooleanArray_ti,
    MkCharArray_ti;
MkObject *mk_array_new_obj(MkInt length);
MkObject *mk_array_new_int(MkInt length);
MkObject *mk_array_new_long(MkInt length);
MkObject *mk_array_new_double(MkInt length);
MkObject *mk_array_new_boolean(MkInt length);
MkObject *mk_array_new_char(MkInt length);
/* Element access is inline (hot loops): an unsigned compare covers both bounds, the failing path is an out-of-line
 * mk_raise_index that leaves ArrayIndexOutOfBoundsException pending (callers test mk_pending_exception afterwards) and
 * the access yields 0 / NULL. The elements start right behind the MkArray header. */
#define MK_ARRAY_ELEMS(type, array) ((type *)(((MkArray *)(array)) + 1))
#define MK_ARRAY_ACCESS(suffix, type, zero)                                                              \
    static inline type mk_array_get_##suffix(const MkObject *array, MkInt index) {                        \
        if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {          \
            mk_raise_index(index, ((const MkArray *)array)->length);                                       \
            return (zero);                                                                                 \
        }                                                                                                  \
        return MK_ARRAY_ELEMS(type, array)[index];                                                         \
    }                                                                                                      \
    static inline void mk_array_set_##suffix(MkObject *array, MkInt index, type value) {                  \
        if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {          \
            mk_raise_index(index, ((const MkArray *)array)->length);                                       \
            return;                                                                                        \
        }                                                                                                  \
        MK_ARRAY_ELEMS(type, array)[index] = value;                                                        \
    }
MK_ARRAY_ACCESS(int, MkInt, 0)
MK_ARRAY_ACCESS(long, MkLong, 0)
MK_ARRAY_ACCESS(double, MkDouble, 0.0)
MK_ARRAY_ACCESS(boolean, MkBoolean, false)
MK_ARRAY_ACCESS(char, MkChar, 0)
#undef MK_ARRAY_ACCESS
static inline MkInt mk_array_length(const MkObject *array) { return ((const MkArray *)array)->length; }
/* get_obj returns an owned reference; set_obj retains the new element and releases the old one. */
static inline MkObject *mk_array_get_obj(const MkObject *array, MkInt index) {
    if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {
        mk_raise_index(index, ((const MkArray *)array)->length);
        return NULL;
    }
    MkObject *v = MK_ARRAY_ELEMS(MkObject *, array)[index];
    mk_retain(v);
    return v;
}
/* Borrowed read for the code generator (RcOpt proved the array keeps the slot alive while the result is used): no retain. */
static inline MkObject *mk_array_get_obj_b(const MkObject *array, MkInt index) {
    if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {
        mk_raise_index(index, ((const MkArray *)array)->length);
        return NULL;
    }
    return MK_ARRAY_ELEMS(MkObject *, array)[index];
}
static inline void mk_array_set_obj(MkObject *array, MkInt index, MkObject *value) {
    if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {
        mk_raise_index(index, ((const MkArray *)array)->length);
        return;
    }
    MkObject **p = &MK_ARRAY_ELEMS(MkObject *, array)[index];
    mk_retain(value);
    MkObject *old = *p;
    *p = value;
    mk_release(old);
}
/* `string[index]`: the UTF-16 unit, or StringIndexOutOfBoundsException (pending) and 0. */
MkChar mk_string_get_char(const MkString *string, MkInt index);

/* Copies all elements of `src` (same kind) into `dst` starting at `index` (vararg spread). */
void mk_array_copy_into(MkObject *dst, MkInt index, const MkObject *src);
/* `contentToString()`: "[1, 2, 3]"; elements of object arrays through toString. NULL gives "null". */
const MkString *mk_array_content_to_string(const MkObject *array);

/* ---- end v1-L ------------------------------------------------------------------- */

/* ---- B1: file access and program arguments (stdlib/mini/internal/B1Intrinsics.kt) ----- */
/* Used by the self-hosted backend driver (compiler/native-driver). Arguments are borrowed,
 * reference results are owned (+1). Text files are UTF-8. */

/* Reads the whole file and decodes it as UTF-8; NULL when it cannot be opened or read. */
MkString *mk_b1_read_file(const MkString *path);
/* Writes `text` as UTF-8 (create/truncate); false when it cannot be written. */
MkBoolean mk_b1_write_file(const MkString *path, const MkString *text);
/* 0 = does not exist, 1 = regular file, 2 = directory (stat, symbolic links followed). */
MkInt mk_b1_file_kind(const MkString *path);
/* The entries of a directory (without "." and ".."), sorted, joined with '\n'; NULL when it is not a readable directory. */
MkString *mk_b1_list_dir(const MkString *path);
/* Creates the directory and its missing parents (mode 0777 & umask); true when it exists afterwards. */
MkBoolean mk_b1_mkdirs(const MkString *path);
/* Removes a file or an empty directory; false when that fails. */
MkBoolean mk_b1_delete(const MkString *path);
/* Stores the process arguments (called by the runtime entry point before mk_main). */
void mk_b1_set_args(int argc, char **argv);
/* `Array<String>` of the command line arguments without the program name (for `fun main(args)`). */
MkObject *mk_b1_args(void);

/* ---- end B1 ----------------------------------------------------------------------- */

/* ---- D1 ---- */
/* Runtime statistics (docs/benchmarks.md). Compile the runtime AND the generated code with
 * -DMK_STATS (`minic build --stats`) to count allocations, frees, retains and releases (split
 * atomic / non-atomic / no-op on NULL and static objects) and the peak of live objects and bytes.
 * A one-line summary per group goes to stderr at exit (lines start with "mk-stats:", key=value).
 * Without the macro none of this exists and the runtime has no extra code or data: zero cost. */
#ifdef MK_STATS
typedef struct MkStats {
    uint64_t allocs;          /* objects allocated (all allocators) */
    uint64_t frees;           /* objects whose memory was returned (mk_free / release at zero) */
    uint64_t live;            /* allocs - frees right now */
    uint64_t peak_live;       /* maximum of `live` */
    uint64_t bytes_total;     /* bytes requested from allocators (including the hidden prefix) */
    uint64_t bytes_live;      /* bytes of objects currently live */
    uint64_t bytes_peak;      /* maximum of `bytes_live` */
    uint64_t retains;         /* calls of mk_retain (including no-ops) */
    uint64_t retains_atomic;  /* ... on objects marked MK_FLAG_SHARED */
    uint64_t retains_noop;    /* ... on NULL, static or stack objects */
    uint64_t releases;        /* calls of mk_release (including no-ops) */
    uint64_t releases_atomic;
    uint64_t releases_noop;
} MkStats;
void mk_stats_get(MkStats *out);  /* consistent enough snapshot of all counters */
void mk_stats_reset(void);        /* zeroes the counters; live/peak restart from the current live set */
void mk_stats_dump(void);         /* writes the summary to stderr (also done by atexit) */
#else
#define mk_stats_reset() ((void)0)
#define mk_stats_dump() ((void)0)
#endif

/* ---- end D1 ---- */

/* ---- D2: Strict code generation (docs/memory-model.md, compiler/README.md "Strict code generation") ---- */

/* Allocator handles for the mini.memory classes: the address of the MkAllocator as a 64-bit integer (0 = the default
 * Rc allocator). The Kotlin classes Heap/Arena/Pool/DebugAllocator keep one in a field; the compiler passes it to
 * mk_alloc_with for allocation sites it routes there. */
int64_t mk_heap_allocator_h(void);
int64_t mk_arena_new_h(void);                       /* mk_arena_create(0) */
void mk_arena_close_h(int64_t arena);               /* mk_arena_destroy */
int64_t mk_pool_new_h(const MkTypeInfo *type, MkInt capacity); /* blocks for instances of `type`, `capacity` per slab */
void mk_pool_close_h(int64_t pool);                 /* mk_pool_destroy */
int64_t mk_debug_new_h(int64_t inner);              /* DebugAllocator over the allocator `inner` (0 = heap) */

/* `x.free()` in generated code: like mk_free for objects of a Deallocating allocator (Heap, Pool, DebugAllocator).
 * NULL is ignored; objects of the reference-counting allocator (Rc) are left to their count; objects of a Scoped
 * allocator panic like mk_free (arena objects cannot be freed individually). */
void mk_free_dealloc(MkObject *object);
/* The free glue of an object that owns `object` in a field (dispose of Strict classes): mk_free for objects of a
 * Deallocating allocator, one mk_release (the owner's count) for reference counted ones, nothing for static/stack. */
void mk_free_owned(MkObject *object);
/* Throws away an object whose constructor failed: released when reference counted, freed when it comes from a
 * Deallocating allocator, left to the arena when Scoped. */
void mk_discard(MkObject *object);

/* ---- end D2 ---- */

#ifdef __cplusplus
}
#endif

#endif
