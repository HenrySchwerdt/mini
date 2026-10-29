/* Class model support: default Any members, virtual toString/equals/hashCode,
 * interface tables, casts and boxed primitives. See the P1-A block in minirt.h. */
#include "internal.h"

#include <inttypes.h>
#include <stdio.h>
#include <string.h>

/* ---- boxes ------------------------------------------------------------------- */

typedef struct MkBoxInt { MkObject header; MkInt value; } MkBoxInt;
typedef struct MkBoxLong { MkObject header; MkLong value; } MkBoxLong;
typedef struct MkBoxBoolean { MkObject header; MkBoolean value; } MkBoxBoolean;
typedef struct MkBoxDouble { MkObject header; MkDouble value; } MkBoxDouble;
typedef struct MkBoxChar { MkObject header; MkChar value; } MkBoxChar;

const MkTypeInfo mk_type_Int = {"kotlin.Int", sizeof(MkBoxInt), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo mk_type_Long = {"kotlin.Long", sizeof(MkBoxLong), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo mk_type_Boolean = {"kotlin.Boolean", sizeof(MkBoxBoolean), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo mk_type_Double = {"kotlin.Double", sizeof(MkBoxDouble), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo mk_type_Char = {"kotlin.Char", sizeof(MkBoxChar), &mk_type_Any, NULL, NULL, 0, NULL, 0};

MkObject *mk_box_int(MkInt value) {
    MkBoxInt *b = (MkBoxInt *)mk_alloc(&mk_type_Int);
    b->value = value;
    return &b->header;
}
MkObject *mk_box_long(MkLong value) {
    MkBoxLong *b = (MkBoxLong *)mk_alloc(&mk_type_Long);
    b->value = value;
    return &b->header;
}
MkObject *mk_box_boolean(MkBoolean value) {
    MkBoxBoolean *b = (MkBoxBoolean *)mk_alloc(&mk_type_Boolean);
    b->value = value;
    return &b->header;
}
MkObject *mk_box_double(MkDouble value) {
    MkBoxDouble *b = (MkBoxDouble *)mk_alloc(&mk_type_Double);
    b->value = value;
    return &b->header;
}
MkObject *mk_box_char(MkChar value) {
    MkBoxChar *b = (MkBoxChar *)mk_alloc(&mk_type_Char);
    b->value = value;
    return &b->header;
}

static const MkObject *unbox_check(const MkObject *box, const MkTypeInfo *type) {
    if (box == NULL) mk_throw_npe();
    if (box->type != type) mk_throw_class_cast(box, type);
    return box;
}
MkInt mk_unbox_int(const MkObject *box) { return ((const MkBoxInt *)unbox_check(box, &mk_type_Int))->value; }
MkLong mk_unbox_long(const MkObject *box) { return ((const MkBoxLong *)unbox_check(box, &mk_type_Long))->value; }
MkBoolean mk_unbox_boolean(const MkObject *box) {
    return ((const MkBoxBoolean *)unbox_check(box, &mk_type_Boolean))->value;
}
MkDouble mk_unbox_double(const MkObject *box) {
    return ((const MkBoxDouble *)unbox_check(box, &mk_type_Double))->value;
}
MkChar mk_unbox_char(const MkObject *box) { return ((const MkBoxChar *)unbox_check(box, &mk_type_Char))->value; }

/* ---- failures ------------------------------------------------------------------ */

void mk_throw_npe(void) { mk_panic("NullPointerException"); }

void mk_throw_class_cast(const MkObject *object, const MkTypeInfo *target) {
    char buf[256];
    snprintf(buf, sizeof buf, "ClassCastException: %s cannot be cast to %s", object->type->name, target->name);
    mk_panic(buf);
}

MkObject *mk_cast(MkObject *object, const MkTypeInfo *target) {
    if (object != NULL && !mk_is_instance(object, target)) mk_throw_class_cast(object, target);
    return object;
}

MkObject *mk_not_null(MkObject *object) {
    if (object == NULL) mk_throw_npe();
    return object;
}

/* ---- interface tables ------------------------------------------------------------ */

void *const *mk_itable(const MkObject *object, const MkTypeInfo *iface) {
    const MkTypeInfo *t = object->type;
    if (t->vtable == NULL) return NULL;
    size_t base = t->vtable_length - t->interface_count;
    for (size_t i = 0; i < t->interface_count; i++) {
        if (t->interfaces[i] == iface) return (void *const *)t->vtable[base + i];
    }
    return NULL;
}

/* ---- Any members -------------------------------------------------------------------- */

MkInt mk_any_hash_code(MkObject *self) { return (MkInt)((uintptr_t)self >> 4); }

bool mk_any_equals(MkObject *self, MkObject *other) { return self == other; }

MkString *mk_any_to_string(MkObject *self) {
    char buf[320];
    int n = snprintf(buf, sizeof buf, "%s@%" PRIx32, self->type->name, (uint32_t)mk_hash_code(self));
    if (n < 0) n = 0;
    if ((size_t)n >= sizeof buf) n = (int)sizeof buf - 1;
    return mk_string_from_utf8(buf, (size_t)n);
}

typedef MkString *(*ToStringFn)(MkObject *);
typedef bool (*EqualsFn)(MkObject *, MkObject *);
typedef MkInt (*HashFn)(MkObject *);

static void *slot(const MkObject *o, size_t index) {
    const MkTypeInfo *t = o->type;
    if (t->vtable == NULL || t->vtable_length <= index) return NULL;
    return t->vtable[index];
}

MkString *mk_to_string(MkObject *o) {
    if (o == NULL) return mk_string_from_utf8("null", 4);
    const MkTypeInfo *t = o->type;
    if (t == &mk_type_String) {
        mk_retain(o);
        return (MkString *)o;
    }
    if (t == &mk_type_Int) return mk_string_from_int(((MkBoxInt *)o)->value);
    if (t == &mk_type_Long) return mk_string_from_long(((MkBoxLong *)o)->value);
    if (t == &mk_type_Boolean) return mk_string_from_boolean(((MkBoxBoolean *)o)->value);
    if (t == &mk_type_Double) return mk_string_from_double(((MkBoxDouble *)o)->value);
    if (t == &mk_type_Char) return mk_string_from_char(((MkBoxChar *)o)->value);
    void *f = slot(o, MK_VSLOT_TO_STRING);
    return f ? ((ToStringFn)f)(o) : mk_any_to_string(o);
}

static MkInt double_bits_hash(MkDouble d) {
    uint64_t bits;
    if (d != d) bits = UINT64_C(0x7ff8000000000000);
    else memcpy(&bits, &d, sizeof bits);
    return (MkInt)(uint32_t)(bits ^ (bits >> 32));
}

MkInt mk_hash_code(MkObject *o) {
    if (o == NULL) return 0;
    const MkTypeInfo *t = o->type;
    if (t == &mk_type_String) return mk_string_hash((MkString *)o);
    if (t == &mk_type_Int) return ((MkBoxInt *)o)->value;
    if (t == &mk_type_Long) {
        uint64_t v = (uint64_t)((MkBoxLong *)o)->value;
        return (MkInt)(uint32_t)(v ^ (v >> 32));
    }
    if (t == &mk_type_Boolean) return ((MkBoxBoolean *)o)->value ? 1231 : 1237;
    if (t == &mk_type_Double) return double_bits_hash(((MkBoxDouble *)o)->value);
    if (t == &mk_type_Char) return (MkInt)((MkBoxChar *)o)->value;
    void *f = slot(o, MK_VSLOT_HASH_CODE);
    return f ? ((HashFn)f)(o) : mk_any_hash_code(o);
}

bool mk_equals(MkObject *a, MkObject *b) {
    if (a == NULL) return b == NULL;
    if (a == b) return true;
    if (b == NULL) return false;
    const MkTypeInfo *t = a->type;
    if (t == &mk_type_String) return b->type == t && mk_string_equals((MkString *)a, (MkString *)b);
    if (t == &mk_type_Int) return b->type == t && ((MkBoxInt *)a)->value == ((MkBoxInt *)b)->value;
    if (t == &mk_type_Long) return b->type == t && ((MkBoxLong *)a)->value == ((MkBoxLong *)b)->value;
    if (t == &mk_type_Boolean) return b->type == t && ((MkBoxBoolean *)a)->value == ((MkBoxBoolean *)b)->value;
    if (t == &mk_type_Char) return b->type == t && ((MkBoxChar *)a)->value == ((MkBoxChar *)b)->value;
    if (t == &mk_type_Double) {
        if (b->type != t) return false;
        MkDouble x = ((MkBoxDouble *)a)->value, y = ((MkBoxDouble *)b)->value;
        if (x != x && y != y) return true; /* boxed Double.equals: NaN equals NaN, 0.0 != -0.0 */
        return memcmp(&x, &y, sizeof x) == 0;
    }
    void *f = slot(a, MK_VSLOT_EQUALS);
    return f ? ((EqualsFn)f)(a, b) : mk_any_equals(a, b);
}
