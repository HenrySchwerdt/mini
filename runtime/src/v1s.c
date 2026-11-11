/* v1-S: native primitives of the Mini stdlib (stdlib/mini/internal/Intrinsics.kt).
 * Convention: arguments are borrowed, reference results are owned (+1). */
#include "internal.h"

#include <ctype.h>
#include <stdlib.h>
#include <string.h>

/* ---- type name / identity hash ---------------------------------------------------- */

MkString *mk_type_name(MkObject *object) {
    if (object == NULL) mk_panic("NullPointerException");
    const char *name = object->type->name;
    return mk_string_from_utf8(name, strlen(name));
}

/* Is the type named [name] (a fully qualified Kotlin name) the dynamic type of [object], one of its superclasses or one of its
 * (transitively) implemented interfaces? What KClass.isInstance asks. */
static bool type_named(const MkTypeInfo *t, const char *name) {
    for (; t != NULL; t = t->super) {
        if (strcmp(t->name, name) == 0) return true;
        for (size_t i = 0; i < t->interface_count; i++) {
            if (t->interfaces[i] != NULL && type_named(t->interfaces[i], name)) return true;
        }
    }
    return false;
}

MkBoolean mk_type_is_named(MkObject *object, const MkString *name) {
    if (object == NULL || name == NULL) return 0;
    size_t length = 0;
    char *utf8 = mk_string_to_utf8(name, &length);
    bool r = type_named(object->type, utf8);
    free(utf8);
    return r;
}

MkInt mk_identity_hash(MkObject *object) {
    uintptr_t p = (uintptr_t)object;
    uint64_t x = (uint64_t)p;
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdULL;
    x ^= x >> 33;
    return (MkInt)(x & 0x7fffffff);
}

/* ---- strings ------------------------------------------------------------------------ */

MkChar mk_string_char_at(const MkString *s, MkInt index) {
    if (s == NULL) mk_panic("NullPointerException");
    if (index < 0 || index >= s->length) mk_panic("StringIndexOutOfBoundsException: index out of range");
    return mk_string_at(s, index);
}

MkInt mk_string_compare(const MkString *a, const MkString *b) {
    if (a == NULL || b == NULL) mk_panic("NullPointerException");
    MkInt n = a->length < b->length ? a->length : b->length;
    for (MkInt i = 0; i < n; i++) {
        MkChar x = mk_string_at(a, i), y = mk_string_at(b, i);
        if (x != y) return (MkInt)x - (MkInt)y;
    }
    return a->length - b->length;
}

/* Java's Double.parseDouble grammar (decimal form), after trimming characters <= ' '.
 * Copies the cleaned ASCII text into `out` (capacity n+1). */
static int clean_double(const MkString *s, char *out, size_t cap) {
    MkInt b = 0, e = s->length;
    while (b < e && mk_string_at(s, b) <= ' ') b++;
    while (e > b && mk_string_at(s, e - 1) <= ' ') e--;
    if (b == e || (size_t)(e - b) + 1 > cap) return 0;
    size_t n = 0;
    for (MkInt i = b; i < e; i++) {
        MkChar ch = mk_string_at(s, i);
        if (ch > 126) return 0;
        out[n++] = (char)ch;
    }
    out[n] = '\0';
    size_t i = 0;
    if (out[i] == '+' || out[i] == '-') i++;
    if (strcmp(out + i, "NaN") == 0 || strcmp(out + i, "Infinity") == 0) return 1;
    size_t digits = 0;
    while (isdigit((unsigned char)out[i])) { i++; digits++; }
    if (out[i] == '.') {
        i++;
        while (isdigit((unsigned char)out[i])) { i++; digits++; }
    }
    if (digits == 0) return 0;
    if (out[i] == 'e' || out[i] == 'E') {
        i++;
        if (out[i] == '+' || out[i] == '-') i++;
        size_t ed = 0;
        while (isdigit((unsigned char)out[i])) { i++; ed++; }
        if (ed == 0) return 0;
    }
    if (out[i] == 'f' || out[i] == 'F' || out[i] == 'd' || out[i] == 'D') {
        if (out[i + 1] != '\0') return 0;
        out[i] = '\0'; /* the type suffix is accepted and ignored */
        return 1;
    }
    return out[i] == '\0';
}

MkBoolean mk_string_is_double(const MkString *s) {
    if (s == NULL) mk_panic("NullPointerException");
    char buf[128];
    return clean_double(s, buf, sizeof buf) != 0;
}

MkDouble mk_string_parse_double(const MkString *s) {
    if (s == NULL) mk_panic("NullPointerException");
    char buf[128];
    if (!clean_double(s, buf, sizeof buf)) mk_panic("NumberFormatException");
    const char *p = buf;
    int neg = 0;
    if (*p == '+') p++;
    else if (*p == '-') { neg = 1; p++; }
    if (strcmp(p, "NaN") == 0) return strtod("nan", NULL);
    if (strcmp(p, "Infinity") == 0) return neg ? -strtod("inf", NULL) : strtod("inf", NULL);
    return strtod(buf, NULL);
}

/* ---- StringBuilder storage ------------------------------------------------------------
 * The buffer is "narrow" (one byte per character) while every character appended so far is below 256, and becomes "wide" (UTF-16)
 * with the first one that is not: texts of ASCII/Latin-1 characters take half the memory and half the bandwidth while they are built.
 * `mk_sb_to_string` makes a one-byte (Latin-1) MkString from a narrow buffer, a UTF-16 one from a wide buffer. */


#define SB_STEAL_MIN 65536 /* characters: from here on mk_sb_to_string shares the buffer instead of copying it */
#define MK_SB_INLINE_BYTES 32 /* the inline storage: 32 narrow or 16 wide characters */

static inline int sb_is_inline(const MkSb *sb) { return sb->data == (const void *)sb->inline_chars; }

/* A heap buffer has room in front of its characters for the block prefix and the MkString header: a big narrow buffer can then
 * become the Latin-1 string of mk_sb_to_string in place (no copy of 100 MB of text, no second copy in the peak memory). */
#define SB_FRONT (MK_PREFIX_SIZE + offsetof(MkString, chars))

static void *sb_buf_new(size_t bytes) {
    if (bytes > SIZE_MAX - SB_FRONT) mk_panic("out of memory");
    char *base = malloc(SB_FRONT + bytes);
    if (base == NULL) mk_panic("out of memory");
    return base + SB_FRONT;
}

static void *sb_buf_resize(void *data, size_t bytes) {
    if (bytes > SIZE_MAX - SB_FRONT) mk_panic("out of memory");
    char *base = realloc((char *)data - SB_FRONT, SB_FRONT + bytes);
    if (base == NULL) mk_panic("out of memory");
    return base + SB_FRONT;
}

static void sb_buf_free(void *data) { free((char *)data - SB_FRONT); }

static void sb_dispose(MkObject *self) {
    MkSb *sb = (MkSb *)self;
    if (sb->backing != NULL) {
        mk_release(&sb->backing->header);
        sb->backing = NULL;
    } else if (!sb_is_inline(sb)) {
        sb_buf_free(sb->data);
    }
    sb->data = NULL;
}

/* The characters of the builder are those of a string it shares (mk_sb_to_string): copy them before anything is changed. */
static void sb_unshare(MkSb *sb) {
    MkString *b = sb->backing;
    if (b == NULL) return;
    size_t n = (size_t)sb->length;
    void *d = sb_buf_new(n > 0 ? n : 1);
    if (n > 0) memcpy(d, sb->data, n);
    sb->data = d;
    sb->capacity = (MkInt)n;
    sb->backing = NULL;
    mk_release(&b->header);
}

static const MkTypeInfo sb_type = {
    "mini.internal.StringBuilderBuffer", sizeof(MkSb), &mk_type_Any, sb_dispose, NULL, 0, NULL, 0, NULL, NULL,
};

static MkSb *sb_of(MkObject *o) {
    if (o == NULL) mk_panic("NullPointerException");
    return (MkSb *)o;
}

/* narrow -> wide, content kept */
static void sb_widen(MkSb *sb) {
    if (sb->wide) return;
    MkInt len = sb->length;
    const uint8_t *src = (const uint8_t *)sb->data;
    if (sb_is_inline(sb) && len <= MK_SB_INLINE_BYTES / 2) {
        MkChar *dst = sb->inline_chars; /* the same bytes: widen backwards, in place */
        for (MkInt i = len - 1; i >= 0; i--) dst[i] = src[i];
        sb->capacity = MK_SB_INLINE_BYTES / 2;
        sb->wide = 1;
        return;
    }
    MkInt cap = sb->capacity > len ? sb->capacity : len;
    if (cap < MK_SB_INLINE_BYTES / 2) cap = MK_SB_INLINE_BYTES / 2;
    MkChar *n = sb_buf_new((size_t)cap * sizeof(MkChar));
    for (MkInt i = 0; i < len; i++) n[i] = src[i];
    if (sb->backing != NULL) {
        mk_release(&sb->backing->header);
        sb->backing = NULL;
    } else if (!sb_is_inline(sb)) {
        sb_buf_free(sb->data);
    }
    sb->data = n;
    sb->capacity = cap;
    sb->wide = 1;
}

static void sb_grow(MkSb *sb, MkInt need) {
    MkInt cap = sb->capacity < MK_SB_INLINE_BYTES / 2 ? MK_SB_INLINE_BYTES / 2 : sb->capacity;
    while (cap < need) {
        if (cap > INT32_MAX / 2) { cap = need; break; }
        cap *= 2;
    }
    size_t esz = sb->wide ? sizeof(MkChar) : 1;
    void *n;
    if (sb->backing != NULL) { /* the characters are a string's: the new buffer is made and filled in one step */
        MkString *old = sb->backing;
        n = sb_buf_new((size_t)cap * esz);
        if (sb->length > 0) memcpy(n, sb->data, (size_t)sb->length * esz);
        sb->backing = NULL;
        mk_release(&old->header);
    } else if (sb_is_inline(sb)) {
        n = sb_buf_new((size_t)cap * esz);
        if (sb->length > 0) memcpy(n, sb->data, (size_t)sb->length * esz);
    } else {
        n = sb_buf_resize(sb->data, (size_t)cap * esz);
    }
    sb->data = n;
    sb->capacity = cap;
}

static inline void sb_reserve(MkSb *sb, MkInt extra) {
    if (extra < 0 || sb->length > INT32_MAX - extra) mk_panic("OutOfMemoryError: string too long");
    MkInt need = sb->length + extra;
    if (need > sb->capacity) sb_grow(sb, need);
}

MkObject *mk_sb_new(void) {
    size_t bytes = sizeof(MkSb) + MK_SB_INLINE_BYTES;
    MkSb *sb = (MkSb *)mk_alloc_sized(NULL, &sb_type, bytes);
    if (sb == NULL) mk_panic("out of memory");
    sb->data = sb->inline_chars;
    sb->capacity = MK_SB_INLINE_BYTES;
    sb->wide = 0;
    return &sb->header;
}

void mk_sb_append_string(MkObject *o, const MkString *s) {
    MkSb *sb = sb_of(o);
    if (s == NULL) mk_panic("NullPointerException");
    MkInt n = s->length;
    if (n == 0) return;
    sb_reserve(sb, n);
    if (mk_string_is_latin1(s)) {
        if (!sb->wide) {
            memcpy((uint8_t *)sb->data + sb->length, mk_string_bytes(s), (size_t)n);
        } else {
            const uint8_t *src = mk_string_bytes(s);
            MkChar *d = (MkChar *)sb->data + sb->length;
            for (MkInt i = 0; i < n; i++) d[i] = src[i];
        }
        sb->length += n;
        return;
    }
    if (!sb->wide) {
        uint8_t *d = (uint8_t *)sb->data + sb->length;
        MkChar all = 0;
        for (MkInt i = 0; i < n; i++) {
            MkChar c = s->chars[i];
            all |= c;
            d[i] = (uint8_t)c;
        }
        if (all < 256) {
            sb->length += n;
            return;
        }
        sb_widen(sb); /* a wide character: the narrow bytes written above are beyond `length` and ignored */
        sb_reserve(sb, n);
    }
    memcpy((MkChar *)sb->data + sb->length, s->chars, (size_t)n * sizeof(MkChar));
    sb->length += n;
}

void mk_sb_append_char(MkObject *o, MkChar c) {
    MkSb *sb = sb_of(o);
    MkInt len = sb->length;
    if (len < sb->capacity) {
        if (sb->wide) {
            ((MkChar *)sb->data)[len] = c;
            sb->length = len + 1;
            return;
        }
        if (c < 256) {
            ((uint8_t *)sb->data)[len] = (uint8_t)c;
            sb->length = len + 1;
            return;
        }
    }
    if (!sb->wide && c >= 256) sb_widen(sb);
    sb_reserve(sb, 1);
    if (sb->wide) ((MkChar *)sb->data)[sb->length++] = c;
    else ((uint8_t *)sb->data)[sb->length++] = (uint8_t)c;
}

/* The digits go straight into the buffer: no temporary string. */
void mk_sb_append_long(MkObject *o, MkLong value) {
    MkSb *sb = sb_of(o);
    size_t n = mk_i64_length(value);
    sb_reserve(sb, (MkInt)n);
    if (sb->wide) mk_i64_write(value, (MkChar *)sb->data + sb->length, n);
    else mk_i64_write8(value, (uint8_t *)sb->data + sb->length, n);
    sb->length += (MkInt)n;
}

void mk_sb_append_int(MkObject *o, MkInt value) { mk_sb_append_long(o, value); }

MkInt mk_sb_length(MkObject *o) { return sb_of(o)->length; }

MkChar mk_sb_char_at(MkObject *o, MkInt index) {
    MkSb *sb = sb_of(o);
    if (index < 0 || index >= sb->length) mk_panic("IndexOutOfBoundsException");
    return sb->wide ? ((MkChar *)sb->data)[index] : (MkChar)((uint8_t *)sb->data)[index];
}

void mk_sb_set_char_at(MkObject *o, MkInt index, MkChar c) {
    MkSb *sb = sb_of(o);
    if (index < 0 || index >= sb->length) mk_panic("IndexOutOfBoundsException");
    sb_unshare(sb);
    if (!sb->wide && c >= 256) sb_widen(sb);
    if (sb->wide) ((MkChar *)sb->data)[index] = c;
    else ((uint8_t *)sb->data)[index] = (uint8_t)c;
}

void mk_sb_set_length(MkObject *o, MkInt length) {
    MkSb *sb = sb_of(o);
    if (length < 0) mk_panic("IndexOutOfBoundsException");
    if (length != sb->length) sb_unshare(sb);
    if (length > sb->length) {
        sb_reserve(sb, length - sb->length);
        size_t esz = sb->wide ? sizeof(MkChar) : 1;
        memset((unsigned char *)sb->data + (size_t)sb->length * esz, 0, (size_t)(length - sb->length) * esz);
    }
    sb->length = length;
}

MkString *mk_sb_to_string(MkObject *o) {
    MkSb *sb = sb_of(o);
    MkInt n = sb->length;
    if (sb->backing != NULL) { /* nothing changed since the last toString: strings are immutable, share it */
        mk_retain(&sb->backing->header);
        return sb->backing;
    }
    if (!sb->wide) { /* one byte per character: so is the string */
#ifndef MK_STATS
        if (n >= SB_STEAL_MIN && !sb_is_inline(sb)) {
            /* A big buffer becomes the string itself: the block is shrunk to the text, given the block prefix and the string header
             * (room was left in front of the characters), and shared with the builder, which copies the text only if it is changed
             * (sb_unshare). The builder of a text that is built and then turned into a string once no longer needs a second copy. */
            size_t total = SB_FRONT + (size_t)n;
            char *base = realloc((char *)sb->data - SB_FRONT, total);
            if (base != NULL) {
                *(MkAllocator **)(void *)base = mk_rc_allocator();
                *(size_t *)(void *)(base + sizeof(void *)) = total;
                MkString *r = (MkString *)(void *)(base + MK_PREFIX_SIZE);
                r->header.type = &mk_type_String;
                r->header.refcount = 2; /* the caller and the builder */
                r->header.flags = MK_FLAG_PREFIXED | MK_FLAG_LATIN1;
                r->length = n;
                sb->data = (void *)r->chars;
                sb->capacity = n;
                sb->backing = r;
                return r;
            }
        }
#endif
        MkString *r = mk_string_alloc8(n);
        if (n > 0) memcpy((uint8_t *)(void *)r->chars, sb->data, (size_t)n);
        return r;
    }
    MkString *r = mk_string_alloc(n);
    if (n > 0) memcpy(r->chars, sb->data, (size_t)n * sizeof(MkChar));
    return r;
}

/* ---- reference arrays ------------------------------------------------------------------ */

/* The layout is that of MkArray (elements right behind the length, 8-byte aligned): generated code reads and writes the
 * slots inline (the mkh_ra_* helpers of the C prelude, the same in the LLVM lowering). */
typedef struct MkRefArray {
    MkObject header;
    MkInt length;
    MkObject *items[];
} MkRefArray;

static void ref_array_dispose(MkObject *self) {
    MkRefArray *a = (MkRefArray *)self;
    for (MkInt i = 0; i < a->length; i++) {
        MkObject *item = a->items[i];
        if (item == NULL) continue;
#if !defined(MK_STATS)
        /* a reference to an object that stays alive (a graph's nodes are held by many lists): the decrement of mk_release, without
         * the call. Anything else (static, shared, a candidate not yet buffered, the last reference, threads running) takes it. */
        if (!__atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED) &&
            (item->flags & (MK_FLAG_STATIC | MK_FLAG_STACK | MK_FLAG_SHARED | MK_FLAG_ARMED)) == 0 && item->refcount > 1u) {
            item->refcount--;
            continue;
        }
#endif
        mk_release(item);
    }
}

static void ref_array_trace(MkObject *self, void (*visit)(MkObject *, void *), void *ctx) {
    MkRefArray *a = (MkRefArray *)self;
    for (MkInt i = 0; i < a->length; i++) visit(a->items[i], ctx);
}

static const MkTypeInfo ref_array_type = {
    "mini.internal.RefArray", sizeof(MkRefArray), &mk_type_Any, ref_array_dispose, NULL, 0, NULL, 0, ref_array_trace, NULL,
};

static MkRefArray *ra_of(MkObject *o, MkInt index) {
    if (o == NULL) mk_panic("NullPointerException");
    MkRefArray *a = (MkRefArray *)o;
    if (index < 0 || index >= a->length) mk_panic("ArrayIndexOutOfBoundsException");
    return a;
}

MkObject *mk_ref_array_new(MkInt size) {
    if (size < 0) mk_panic("NegativeArraySizeException");
    size_t count = (size_t)size;
    if (count > (SIZE_MAX - sizeof(MkRefArray)) / sizeof(MkObject *)) mk_panic("out of memory");
    size_t bytes = sizeof(MkRefArray) + count * sizeof(MkObject *);
    mk_cycles_ensure();
    MkRefArray *a = (MkRefArray *)mk_alloc_sized(NULL, &ref_array_type, bytes);
    if (a == NULL) mk_panic("out of memory");
    a->length = size;
    return &a->header;
}

MkObject *mk_ref_array_get(MkObject *o, MkInt index) {
    MkObject *v = ra_of(o, index)->items[index];
    mk_retain(v);
    return v;
}

/* D2 conventions (compiler/README.md): a borrowed read and a store that takes over the caller's reference */
MkObject *mk_ref_array_get_b(MkObject *o, MkInt index) { return ra_of(o, index)->items[index]; }

void mk_ref_array_set_o(MkObject *o, MkInt index, MkObject *value) {
    MkRefArray *a = ra_of(o, index);
    MkObject *old = a->items[index];
    a->items[index] = value;
    mk_release(old);
}

void mk_ref_array_set(MkObject *o, MkInt index, MkObject *value) {
    MkRefArray *a = ra_of(o, index);
    MkObject *old = a->items[index];
    mk_retain(value);
    a->items[index] = value;
    mk_release(old);
}

void mk_ref_array_move(MkObject *dst, MkObject *src, MkInt count) {
    if (dst == NULL || src == NULL) mk_panic("NullPointerException");
    MkRefArray *d = (MkRefArray *)dst, *s = (MkRefArray *)src;
    if (count < 0 || count > d->length || count > s->length) mk_panic("ArrayIndexOutOfBoundsException");
    for (MkInt i = 0; i < count; i++) {
        MkObject *old = d->items[i]; /* NULL by contract; released anyway so that a misuse cannot leak */
        d->items[i] = s->items[i];
        s->items[i] = NULL;
        if (old != NULL) mk_release(old);
    }
}

MkInt mk_ref_array_length(MkObject *o) {
    if (o == NULL) mk_panic("NullPointerException");
    return ((MkRefArray *)o)->length;
}

/* ---- failures ---------------------------------------------------------------------------- */

static _Noreturn void throw_kind(const char *kind, const MkString *message) {
    MkString *prefix = mk_string_from_utf8(kind, strlen(kind));
    if (message == NULL) mk_panic_string(prefix);
    MkString *sep = mk_string_from_utf8(": ", 2);
    MkString *a = mk_string_concat(prefix, sep);
    MkString *full = mk_string_concat(a, message);
    mk_panic_string(full);
}

#define MK_THROW(fn, kind) \
    _Noreturn void fn(const MkString *message) { throw_kind(kind, message); }

MK_THROW(mk_throw_illegal_argument, "IllegalArgumentException")
MK_THROW(mk_throw_illegal_state, "IllegalStateException")
MK_THROW(mk_throw_index_out_of_bounds, "IndexOutOfBoundsException")
MK_THROW(mk_throw_no_such_element, "NoSuchElementException")
MK_THROW(mk_throw_number_format, "NumberFormatException")
MK_THROW(mk_throw_unsupported_operation, "UnsupportedOperationException")
MK_THROW(mk_throw_not_implemented, "NotImplementedError")
