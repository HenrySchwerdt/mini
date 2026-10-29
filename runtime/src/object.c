/* Reference counting, type tests and the built-in Any type. */
#include "internal.h"

const MkTypeInfo mk_type_Any = {
    "kotlin.Any", sizeof(MkObject), NULL, NULL, NULL, 0, NULL, 0,
};

static bool is_plain(const MkObject *o) {
    return (o->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) != 0;
}

void mk_retain(MkObject *object) {
    if (object == NULL || is_plain(object)) return;
    if (object->flags & MK_FLAG_SHARED) {
        uint32_t old = mk_atomic_fetch_add(&object->refcount, 1u);
        if (old == 0u || old == UINT32_MAX) mk_panic("reference count overflow or resurrection");
    } else {
        if (object->refcount == 0u || object->refcount == UINT32_MAX)
            mk_panic("reference count overflow or resurrection");
        object->refcount++;
    }
}

void mk_release(MkObject *object) {
    if (object == NULL || is_plain(object)) return;
    uint32_t old;
    if (object->flags & MK_FLAG_SHARED) {
        old = mk_atomic_fetch_add(&object->refcount, UINT32_MAX); /* -1 */
    } else {
        old = object->refcount;
        if (old != 0u) object->refcount = old - 1u;
    }
    if (old == 0u) mk_panic("reference count underflow");
    if (old != 1u) return;
    for (const MkTypeInfo *t = object->type; t != NULL; t = t->super) {
        if (t->dispose != NULL) t->dispose(object);
    }
    mk_free_object_memory(object);
}

void mk_share(MkObject *object) {
    if (object == NULL || is_plain(object)) return;
    mk_atomic_fetch_or(&object->flags, MK_FLAG_SHARED);
}

static bool type_is(const MkTypeInfo *t, const MkTypeInfo *target) {
    for (; t != NULL; t = t->super) {
        if (t == target) return true;
        for (size_t i = 0; i < t->interface_count; i++) {
            if (t->interfaces[i] != NULL && type_is(t->interfaces[i], target)) return true;
        }
    }
    return false;
}

bool mk_is_instance(const MkObject *object, const MkTypeInfo *type) {
    if (object == NULL || type == NULL) return false;
    return type_is(object->type, type);
}
