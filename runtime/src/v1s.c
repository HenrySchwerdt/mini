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
    return s->chars[index];
}

MkInt mk_string_compare(const MkString *a, const MkString *b) {
    if (a == NULL || b == NULL) mk_panic("NullPointerException");
    MkInt n = a->length < b->length ? a->length : b->length;
    for (MkInt i = 0; i < n; i++) {
        if (a->chars[i] != b->chars[i]) return (MkInt)a->chars[i] - (MkInt)b->chars[i];
    }
    return a->length - b->length;
}

MkString *mk_string_substring(const MkString *s, MkInt start, MkInt end) {
    if (s == NULL) mk_panic("NullPointerException");
    if (start < 0 || end > s->length || start > end) mk_panic("StringIndexOutOfBoundsException: bad range");
    MkString *r = mk_string_alloc(end - start);
    if (end > start) memcpy(r->chars, s->chars + start, (size_t)(end - start) * sizeof(MkChar));
    return r;
}

/* Java's Double.parseDouble grammar (decimal form), after trimming characters <= ' '.
 * Copies the cleaned ASCII text into `out` (capacity n+1). */
static int clean_double(const MkString *s, char *out, size_t cap) {
    MkInt b = 0, e = s->length;
    while (b < e && s->chars[b] <= ' ') b++;
    while (e > b && s->chars[e - 1] <= ' ') e--;
    if (b == e || (size_t)(e - b) + 1 > cap) return 0;
    size_t n = 0;
    for (MkInt i = b; i < e; i++) {
        if (s->chars[i] > 126) return 0;
        out[n++] = (char)s->chars[i];
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

/* ---- StringBuilder storage ------------------------------------------------------------ */

typedef struct MkSb {
    MkObject header;
    MkChar *chars;
    MkInt length;
    MkInt capacity;
    MkChar inline_chars[];
} MkSb;

#define MK_SB_INLINE_CAPACITY 16

static void sb_dispose(MkObject *self) {
    MkSb *sb = (MkSb *)self;
    if (sb->chars != sb->inline_chars) free(sb->chars);
    ((MkSb *)self)->chars = NULL;
}

static const MkTypeInfo sb_type = {
    "mini.internal.StringBuilderBuffer", sizeof(MkSb), &mk_type_Any, sb_dispose, NULL, 0, NULL, 0,
};

static MkSb *sb_of(MkObject *o) {
    if (o == NULL) mk_panic("NullPointerException");
    return (MkSb *)o;
}

static void sb_reserve(MkSb *sb, MkInt extra) {
    if (extra < 0 || sb->length > INT32_MAX - extra) mk_panic("OutOfMemoryError: string too long");
    MkInt need = sb->length + extra;
    if (need <= sb->capacity) return;
    MkInt cap = sb->capacity < MK_SB_INLINE_CAPACITY ? MK_SB_INLINE_CAPACITY : sb->capacity;
    while (cap < need) {
        if (cap > INT32_MAX / 2) { cap = need; break; }
        cap *= 2;
    }
    MkChar *n;
    if (sb->chars == sb->inline_chars) {
        n = malloc((size_t)cap * sizeof(MkChar));
        if (n != NULL && sb->length > 0) {
            memcpy(n, sb->chars, (size_t)sb->length * sizeof(MkChar));
        }
    } else {
        n = realloc(sb->chars, (size_t)cap * sizeof(MkChar));
    }
    if (n == NULL) mk_panic("out of memory");
    sb->chars = n;
    sb->capacity = cap;
}

MkObject *mk_sb_new(void) {
    size_t bytes = sizeof(MkSb) + MK_SB_INLINE_CAPACITY * sizeof(MkChar);
    MkSb *sb = (MkSb *)mk_alloc_sized(NULL, &sb_type, bytes);
    if (sb == NULL) mk_panic("out of memory");
    sb->chars = sb->inline_chars;
    sb->capacity = MK_SB_INLINE_CAPACITY;
    return &sb->header;
}

void mk_sb_append_string(MkObject *o, const MkString *s) {
    MkSb *sb = sb_of(o);
    if (s == NULL) mk_panic("NullPointerException");
    sb_reserve(sb, s->length);
    if (s->length > 0) memcpy(sb->chars + sb->length, s->chars, (size_t)s->length * sizeof(MkChar));
    sb->length += s->length;
}

void mk_sb_append_char(MkObject *o, MkChar c) {
    MkSb *sb = sb_of(o);
    sb_reserve(sb, 1);
    sb->chars[sb->length++] = c;
}

/* The digits go straight into the buffer: no temporary string. */
void mk_sb_append_long(MkObject *o, MkLong value) {
    MkSb *sb = sb_of(o);
    sb_reserve(sb, MK_ITOA_MAX);
    char buf[MK_ITOA_MAX];
    size_t n = mk_itoa(value, buf);
    MkChar *out = sb->chars + sb->length;
    for (size_t i = 0; i < n; i++) out[i] = (MkChar)(unsigned char)buf[i];
    sb->length += (MkInt)n;
}

void mk_sb_append_int(MkObject *o, MkInt value) { mk_sb_append_long(o, value); }

MkInt mk_sb_length(MkObject *o) { return sb_of(o)->length; }

MkChar mk_sb_char_at(MkObject *o, MkInt index) {
    MkSb *sb = sb_of(o);
    if (index < 0 || index >= sb->length) mk_panic("IndexOutOfBoundsException");
    return sb->chars[index];
}

void mk_sb_set_char_at(MkObject *o, MkInt index, MkChar c) {
    MkSb *sb = sb_of(o);
    if (index < 0 || index >= sb->length) mk_panic("IndexOutOfBoundsException");
    sb->chars[index] = c;
}

void mk_sb_set_length(MkObject *o, MkInt length) {
    MkSb *sb = sb_of(o);
    if (length < 0) mk_panic("IndexOutOfBoundsException");
    if (length > sb->length) {
        sb_reserve(sb, length - sb->length);
        memset(sb->chars + sb->length, 0, (size_t)(length - sb->length) * sizeof(MkChar));
    }
    sb->length = length;
}

MkString *mk_sb_to_string(MkObject *o) {
    MkSb *sb = sb_of(o);
    MkString *r = mk_string_alloc(sb->length);
    if (sb->length > 0) memcpy(r->chars, sb->chars, (size_t)sb->length * sizeof(MkChar));
    return r;
}

/* ---- reference arrays ------------------------------------------------------------------ */

typedef struct MkRefArray {
    MkObject header;
    MkInt length;
    MkObject **items;
    MkObject *inline_items[];
} MkRefArray;

static void ref_array_dispose(MkObject *self) {
    MkRefArray *a = (MkRefArray *)self;
    for (MkInt i = 0; i < a->length; i++) {
        MkObject *item = a->items[i];
        if (item != NULL) mk_release(item);
    }
    a->items = NULL;
}

static const MkTypeInfo ref_array_type = {
    "mini.internal.RefArray", sizeof(MkRefArray), &mk_type_Any, ref_array_dispose, NULL, 0, NULL, 0,
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
    MkRefArray *a = (MkRefArray *)mk_alloc_sized(NULL, &ref_array_type, bytes);
    if (a == NULL) mk_panic("out of memory");
    a->length = size;
    a->items = count > 0 ? a->inline_items : NULL;
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
