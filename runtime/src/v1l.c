/* v1-L runtime support: exceptions (error-return ABI), checked casts, arrays, compareTo.
 * See the v1-L block in minirt.h. */
#include "internal.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

/* ---- pending exception -------------------------------------------------------------- */

MK_THREAD_LOCAL MkObject *mk_pending_exception = NULL;

void mk_rethrow(MkObject *exception) {
    if (exception == NULL) {
        mk_raise_npe();
        return;
    }
    MkObject *old = mk_pending_exception;
    mk_pending_exception = exception;
    mk_release(old);
}

void mk_throw(MkObject *exception) {
    if (exception == NULL) {
        mk_raise_npe();
        return;
    }
    mk_retain(exception);
    mk_rethrow(exception);
}

MkObject *mk_catch(void) {
    MkObject *e = mk_pending_exception;
    mk_pending_exception = NULL;
    return e;
}

_Noreturn void mk_report_uncaught(void) {
    MkObject *e = mk_catch();
    mk_runtime_flush();
    const MkString *text = e != NULL ? mk_to_string(e) : NULL;
    fputs("Exception in thread \"main\" ", stderr);
    if (text != NULL) {
        size_t len;
        char *u = mk_utf16_to_utf8(text->chars, (size_t)text->length, &len);
        if (u != NULL) {
            fwrite(u, 1, len, stderr);
            free(u);
        }
    }
    fputc('\n', stderr);
    fflush(stderr);
    exit(1);
}

/* ---- Throwable and the built-in exception classes ------------------------------------ */

typedef const MkString *(*MessageFn)(MkObject *);

const MkString *mk_throwable_message(MkObject *self) {
    const MkString *m = ((MkThrowable *)self)->message;
    mk_retain((MkObject *)m);
    return m;
}

static const MkString *utf8(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

/* "<class name>[: <message>]" like java.lang.Throwable.toString (message through the vtable). */
const MkString *mk_throwable_to_string(MkObject *self) {
    const char *name = self->type->name;
    const MkTypeInfo *t = self->type;
    const MkString *msg = NULL;
    if (t->vtable != NULL && t->vtable_length > 3) msg = ((MessageFn)t->vtable[3])(self);
    const MkString *head = utf8(name);
    if (msg == NULL) return head;
    const MkString *sep = utf8(": ");
    const MkString *a = mk_string_concat(head, sep);
    const MkString *r = mk_string_concat(a, msg);
    mk_release((MkObject *)head);
    mk_release((MkObject *)sep);
    mk_release((MkObject *)a);
    mk_release((MkObject *)msg);
    return r;
}

static void throwable_dispose(MkObject *self) {
    MkThrowable *t = (MkThrowable *)self;
    mk_release((MkObject *)t->message);
    mk_release(t->cause);
}

static void *const throwable_vtable[] = {
    (void *)mk_throwable_to_string, (void *)mk_any_equals, (void *)mk_any_hash_code, (void *)mk_throwable_message,
};

#define EXC(Name, qualified, Super, dispose)                                                       \
    const MkTypeInfo Name##_ti = {qualified, sizeof(MkThrowable), &Super, dispose, throwable_vtable, \
                                  4, NULL, 0};                                                     \
    MkObject *Name##_new(const MkString *message, MkObject *cause) {                               \
        MkThrowable *t = (MkThrowable *)mk_alloc(&Name##_ti);                                      \
        t->message = message;                                                                      \
        t->cause = cause;                                                                          \
        mk_retain((MkObject *)message);                                                            \
        mk_retain(cause);                                                                          \
        return &t->header;                                                                         \
    }

EXC(MkThrowable, "java.lang.Throwable", mk_type_Any, throwable_dispose)
EXC(MkException, "java.lang.Exception", MkThrowable_ti, NULL)
EXC(MkRuntimeException, "java.lang.RuntimeException", MkException_ti, NULL)
EXC(MkError, "java.lang.Error", MkThrowable_ti, NULL)
EXC(MkIllegalStateException, "java.lang.IllegalStateException", MkRuntimeException_ti, NULL)
EXC(MkIllegalArgumentException, "java.lang.IllegalArgumentException", MkRuntimeException_ti, NULL)
EXC(MkNullPointerException, "java.lang.NullPointerException", MkRuntimeException_ti, NULL)
EXC(MkArithmeticException, "java.lang.ArithmeticException", MkRuntimeException_ti, NULL)
EXC(MkClassCastException, "java.lang.ClassCastException", MkRuntimeException_ti, NULL)
EXC(MkIndexOutOfBoundsException, "java.lang.IndexOutOfBoundsException", MkRuntimeException_ti, NULL)
EXC(MkArrayIndexOutOfBoundsException, "java.lang.ArrayIndexOutOfBoundsException", MkIndexOutOfBoundsException_ti, NULL)
EXC(MkStringIndexOutOfBoundsException, "java.lang.StringIndexOutOfBoundsException", MkIndexOutOfBoundsException_ti, NULL)
EXC(MkUnsupportedOperationException, "java.lang.UnsupportedOperationException", MkRuntimeException_ti, NULL)
EXC(MkNoSuchElementException, "java.util.NoSuchElementException", MkRuntimeException_ti, NULL)
EXC(MkNumberFormatException, "java.lang.NumberFormatException", MkIllegalArgumentException_ti, NULL)
EXC(MkUninitializedPropertyAccessException, "kotlin.UninitializedPropertyAccessException", MkRuntimeException_ti, NULL)
EXC(MkNotImplementedError, "kotlin.NotImplementedError", MkError_ti, NULL)
EXC(MkNegativeArraySizeException, "java.lang.NegativeArraySizeException", MkRuntimeException_ti, NULL)
EXC(MkAssertionError, "java.lang.AssertionError", MkError_ti, NULL)

void mk_throwable_init(MkObject *self, const MkString *message, MkObject *cause) {
    MkThrowable *t = (MkThrowable *)self;
    mk_retain((MkObject *)message);
    mk_retain(cause);
    const MkString *oldm = t->message;
    MkObject *oldc = t->cause;
    t->message = message;
    t->cause = cause;
    mk_release((MkObject *)oldm);
    mk_release(oldc);
}

const MkString *mk_throwable_cause_message(MkObject *cause) {
    if (cause == NULL) return NULL;
    return mk_to_string(cause);
}

MkObject *mk_throwable_cause(MkObject *self) {
    MkObject *c = ((MkThrowable *)self)->cause;
    mk_retain(c);
    return c;
}

void mk_throwable_print_stack_trace(MkObject *self) {
    mk_runtime_flush();
    const MkString *s = mk_to_string(self);
    size_t len;
    char *u = mk_utf16_to_utf8(s->chars, (size_t)s->length, &len);
    if (u != NULL) {
        fwrite(u, 1, len, stderr);
        free(u);
    }
    fputc('\n', stderr);
    mk_release((MkObject *)s);
}

/* ---- raising -------------------------------------------------------------------------- */

void mk_raise(const MkTypeInfo *type, const char *message) {
    const MkString *m = message != NULL ? utf8(message) : NULL;
    MkThrowable *t = (MkThrowable *)mk_alloc(type);
    t->message = m; /* takes the reference of mk_string_from_utf8 */
    mk_rethrow(&t->header);
}

void mk_raise_npe(void) { mk_raise(&MkNullPointerException_ti, NULL); }

void mk_raise_class_cast(const MkObject *object, const MkTypeInfo *target) {
    char buf[320];
    snprintf(buf, sizeof buf, "class %s cannot be cast to class %s", object->type->name, target->name);
    mk_raise(&MkClassCastException_ti, buf);
}

void mk_raise_arith(const char *message) { mk_raise(&MkArithmeticException_ti, message); }

void mk_raise_index(MkInt index, MkInt length) {
    char buf[96];
    snprintf(buf, sizeof buf, "Index %d out of bounds for length %d", (int)index, (int)length);
    mk_raise(&MkArrayIndexOutOfBoundsException_ti, buf);
}

void mk_raise_illegal_argument(const char *message) { mk_raise(&MkIllegalArgumentException_ti, message); }
void mk_raise_illegal_state(const char *message) { mk_raise(&MkIllegalStateException_ti, message); }

void mk_raise_uninitialized(const char *property) {
    char buf[200];
    snprintf(buf, sizeof buf, "lateinit property %s has not been initialized", property);
    mk_raise(&MkUninitializedPropertyAccessException_ti, buf);
}

/* ---- checked casts and unboxing --------------------------------------------------------- */

MkObject *mk_checked_cast(MkObject *object, const MkTypeInfo *target) {
    if (object != NULL && !mk_is_instance(object, target)) {
        mk_raise_class_cast(object, target);
        return NULL;
    }
    return object;
}

MkObject *mk_checked_not_null(MkObject *object) {
    if (object == NULL) mk_raise_npe();
    return object;
}

typedef struct { MkObject h; MkInt v; } BoxInt;
typedef struct { MkObject h; MkLong v; } BoxLong;
typedef struct { MkObject h; MkBoolean v; } BoxBoolean;
typedef struct { MkObject h; MkDouble v; } BoxDouble;
typedef struct { MkObject h; MkChar v; } BoxChar;

static bool unbox_ok(const MkObject *box, const MkTypeInfo *type) {
    if (box == NULL) {
        mk_raise_npe();
        return false;
    }
    if (box->type != type) {
        mk_raise_class_cast(box, type);
        return false;
    }
    return true;
}
MkInt mk_checked_unbox_int(const MkObject *b) { return unbox_ok(b, &mk_type_Int) ? ((const BoxInt *)b)->v : 0; }
MkLong mk_checked_unbox_long(const MkObject *b) { return unbox_ok(b, &mk_type_Long) ? ((const BoxLong *)b)->v : 0; }
MkBoolean mk_checked_unbox_boolean(const MkObject *b) {
    return unbox_ok(b, &mk_type_Boolean) ? ((const BoxBoolean *)b)->v : false;
}
MkDouble mk_checked_unbox_double(const MkObject *b) {
    return unbox_ok(b, &mk_type_Double) ? ((const BoxDouble *)b)->v : 0.0;
}
MkChar mk_checked_unbox_char(const MkObject *b) { return unbox_ok(b, &mk_type_Char) ? ((const BoxChar *)b)->v : 0; }

/* ---- Any, compareTo --------------------------------------------------------------------- */

MkObject *mk_any_new(void) { return mk_alloc(&mk_type_Any); }

static int cmp_ll(long long a, long long b) { return a < b ? -1 : (a > b ? 1 : 0); }

typedef MkInt (*CompareFn)(MkObject *, MkObject *);

MkInt mk_compare_to(MkObject *a, MkObject *b, const MkTypeInfo *comparable) {
    if (a == NULL || b == NULL) {
        mk_raise_npe();
        return 0;
    }
    const MkTypeInfo *t = a->type;
    if (t == &mk_type_Int || t == &mk_type_Long || t == &mk_type_Char || t == &mk_type_Boolean ||
        t == &mk_type_Double || t == &mk_type_String) {
        if (b->type != t) {
            mk_raise_class_cast(b, t);
            return 0;
        }
        if (t == &mk_type_Int) return cmp_ll(((BoxInt *)a)->v, ((BoxInt *)b)->v);
        if (t == &mk_type_Long) return cmp_ll(((BoxLong *)a)->v, ((BoxLong *)b)->v);
        if (t == &mk_type_Char) return cmp_ll(((BoxChar *)a)->v, ((BoxChar *)b)->v);
        if (t == &mk_type_Boolean) return cmp_ll(((BoxBoolean *)a)->v, ((BoxBoolean *)b)->v);
        if (t == &mk_type_Double) {
            double x = ((BoxDouble *)a)->v, y = ((BoxDouble *)b)->v;
            if (x < y) return -1;
            if (x > y) return 1;
            if (x == y && x != 0.0) return 0;
            /* Double.compareTo total order: -0.0 < 0.0, NaN greater than everything, NaN == NaN */
            int xn = x != x, yn = y != y;
            if (xn || yn) return xn == yn ? 0 : (xn ? 1 : -1);
            int xs = signbit(x) != 0, ys = signbit(y) != 0;
            return xs == ys ? 0 : (xs ? -1 : 1);
        }
        const MkString *x = (const MkString *)a, *y = (const MkString *)b;
        MkInt n = x->length < y->length ? x->length : y->length;
        for (MkInt i = 0; i < n; i++) {
            if (x->chars[i] != y->chars[i]) return (MkInt)x->chars[i] - (MkInt)y->chars[i];
        }
        return x->length - y->length;
    }
    void *const *it = comparable != NULL ? mk_itable(a, comparable) : NULL;
    if (it == NULL) {
        mk_raise_class_cast(a, comparable != NULL ? comparable : &mk_type_Any);
        return 0;
    }
    return ((CompareFn)it[0])(a, b);
}

/* ---- arrays ----------------------------------------------------------------------------------- */

static void object_array_dispose(MkObject *self) {
    MkArray *a = (MkArray *)self;
    MkObject **e = (MkObject **)(a + 1);
    for (MkInt i = 0; i < a->length; i++) mk_release(e[i]);
}

const MkTypeInfo MkObjectArray_ti = {"kotlin.Array", sizeof(MkArray), &mk_type_Any, object_array_dispose, NULL, 0, NULL, 0};
const MkTypeInfo MkIntArray_ti = {"kotlin.IntArray", sizeof(MkArray), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo MkLongArray_ti = {"kotlin.LongArray", sizeof(MkArray), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo MkDoubleArray_ti = {"kotlin.DoubleArray", sizeof(MkArray), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo MkBooleanArray_ti = {"kotlin.BooleanArray", sizeof(MkArray), &mk_type_Any, NULL, NULL, 0, NULL, 0};
const MkTypeInfo MkCharArray_ti = {"kotlin.CharArray", sizeof(MkArray), &mk_type_Any, NULL, NULL, 0, NULL, 0};

static MkObject *array_new(const MkTypeInfo *type, MkInt length, size_t elem) {
    if (length < 0) {
        char buf[48];
        snprintf(buf, sizeof buf, "%d", (int)length);
        mk_raise(&MkNegativeArraySizeException_ti, buf);
        return NULL;
    }
    MkObject *o = mk_alloc_sized(NULL, type, sizeof(MkArray) + elem * (size_t)length);
    ((MkArray *)o)->length = length;
    return o;
}

MkObject *mk_array_new_obj(MkInt n) { return array_new(&MkObjectArray_ti, n, sizeof(MkObject *)); }
MkObject *mk_array_new_int(MkInt n) { return array_new(&MkIntArray_ti, n, sizeof(MkInt)); }
MkObject *mk_array_new_long(MkInt n) { return array_new(&MkLongArray_ti, n, sizeof(MkLong)); }
MkObject *mk_array_new_double(MkInt n) { return array_new(&MkDoubleArray_ti, n, sizeof(MkDouble)); }
MkObject *mk_array_new_boolean(MkInt n) { return array_new(&MkBooleanArray_ti, n, sizeof(MkBoolean)); }
MkObject *mk_array_new_char(MkInt n) { return array_new(&MkCharArray_ti, n, sizeof(MkChar)); }

MkInt mk_array_length(const MkObject *array) { return ((const MkArray *)array)->length; }

static bool in_range(const MkObject *array, MkInt i) {
    MkInt n = ((const MkArray *)array)->length;
    if (i < 0 || i >= n) {
        mk_raise_index(i, n);
        return false;
    }
    return true;
}

#define ELEMS(type, array) ((type *)(((MkArray *)(array)) + 1))

MkObject *mk_array_get_obj(const MkObject *a, MkInt i) {
    if (!in_range(a, i)) return NULL;
    MkObject *v = ELEMS(MkObject *, a)[i];
    mk_retain(v);
    return v;
}
void mk_array_set_obj(MkObject *a, MkInt i, MkObject *v) {
    if (!in_range(a, i)) return;
    MkObject **p = &ELEMS(MkObject *, a)[i];
    mk_retain(v);
    MkObject *old = *p;
    *p = v;
    mk_release(old);
}

#define PRIM_ACCESS(suffix, type, zero)                                                        \
    type mk_array_get_##suffix(const MkObject *a, MkInt i) {                                   \
        return in_range(a, i) ? ELEMS(type, a)[i] : (zero);                                    \
    }                                                                                          \
    void mk_array_set_##suffix(MkObject *a, MkInt i, type v) {                                 \
        if (in_range(a, i)) ELEMS(type, a)[i] = v;                                             \
    }
PRIM_ACCESS(int, MkInt, 0)
PRIM_ACCESS(long, MkLong, 0)
PRIM_ACCESS(double, MkDouble, 0.0)
PRIM_ACCESS(boolean, MkBoolean, false)
PRIM_ACCESS(char, MkChar, 0)

void mk_array_copy_into(MkObject *dst, MkInt index, const MkObject *src) {
    const MkArray *s = (const MkArray *)src;
    MkArray *d = (MkArray *)dst;
    if (index < 0 || (int64_t)index + s->length > d->length) {
        mk_raise_index(index, d->length);
        return;
    }
    if (dst->type == &MkObjectArray_ti) {
        MkObject **to = ELEMS(MkObject *, d) + index;
        MkObject *const *from = ELEMS(MkObject *, s);
        for (MkInt i = 0; i < s->length; i++) {
            mk_retain(from[i]);
            MkObject *old = to[i];
            to[i] = from[i];
            mk_release(old);
        }
        return;
    }
    size_t esz = dst->type == &MkIntArray_ti ? sizeof(MkInt)
               : dst->type == &MkLongArray_ti ? sizeof(MkLong)
               : dst->type == &MkDoubleArray_ti ? sizeof(MkDouble)
               : dst->type == &MkBooleanArray_ti ? sizeof(MkBoolean) : sizeof(MkChar);
    memcpy((char *)(d + 1) + esz * (size_t)index, (const char *)(s + 1), esz * (size_t)s->length);
}

/* growable UTF-16 buffer for contentToString */
typedef struct { MkChar *chars; size_t len, cap; } CharBuf;

static void buf_append(CharBuf *b, const MkChar *c, size_t n) {
    if (b->len + n > b->cap) {
        size_t cap = b->cap ? b->cap * 2 : 32;
        while (cap < b->len + n) cap *= 2;
        b->chars = (MkChar *)realloc(b->chars, cap * sizeof(MkChar));
        if (b->chars == NULL) mk_panic("out of memory");
        b->cap = cap;
    }
    memcpy(b->chars + b->len, c, n * sizeof(MkChar));
    b->len += n;
}

const MkString *mk_array_content_to_string(const MkObject *array) {
    if (array == NULL) return mk_string_from_utf8("null", 4);
    const MkArray *a = (const MkArray *)array;
    CharBuf b = {NULL, 0, 0};
    MkChar open = '[', close = ']', sep[2] = {',', ' '};
    buf_append(&b, &open, 1);
    for (MkInt i = 0; i < a->length; i++) {
        if (i > 0) buf_append(&b, sep, 2);
        MkString *e;
        if (array->type == &MkObjectArray_ti) e = mk_to_string(ELEMS(MkObject *, a)[i]);
        else if (array->type == &MkIntArray_ti) e = mk_string_from_int(ELEMS(MkInt, a)[i]);
        else if (array->type == &MkLongArray_ti) e = mk_string_from_long(ELEMS(MkLong, a)[i]);
        else if (array->type == &MkDoubleArray_ti) e = mk_string_from_double(ELEMS(MkDouble, a)[i]);
        else if (array->type == &MkBooleanArray_ti) e = mk_string_from_boolean(ELEMS(MkBoolean, a)[i]);
        else e = mk_string_from_char(ELEMS(MkChar, a)[i]);
        buf_append(&b, e->chars, (size_t)e->length);
        mk_release((MkObject *)e);
    }
    buf_append(&b, &close, 1);
    MkString *r = mk_string_alloc((MkInt)b.len);
    memcpy(r->chars, b.chars, b.len * sizeof(MkChar));
    free(b.chars);
    return r;
}

MkChar mk_string_get_char(const MkString *string, MkInt index) {
    if (index < 0 || index >= string->length) {
        char buf[96];
        snprintf(buf, sizeof buf, "Index %d out of bounds for length %d", (int)index, (int)string->length);
        mk_raise(&MkStringIndexOutOfBoundsException_ti, buf);
        return 0;
    }
    return string->chars[index];
}
