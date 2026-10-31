/* Helpers of WebAssembly library modules (`minic-native --target=wasm32 --emit=wasm-lib`, website/guide/wasm-library.md).
 * The generated export wrappers call mk_wasm_*; the mini_* functions are exported from the module for the JavaScript glue
 * (on wasm32 only; on other targets the exports are plain functions nobody calls). Single threaded: the result registers are statics. */
#include "internal.h"

#include <string.h>

#ifdef __wasm__
#define MINI_EXPORT(name) __attribute__((export_name(name), used))
#else
#define MINI_EXPORT(name)
#endif

static size_t result_len = 0;
static char *error_text = NULL;
static bool error_set = false;

MINI_EXPORT("mini_alloc") void *mini_alloc(size_t size) { return malloc(size != 0 ? size : 1); }

MINI_EXPORT("mini_free") void mini_free(void *p) { free(p); }

/* Releases a handle (a reference the module gave to JavaScript). */
MINI_EXPORT("mini_release") void mini_release(MkObject *handle) { mk_release(handle); }

/* Length (bytes, or elements for arrays) of the result the last string / array export returned. */
MINI_EXPORT("mini_result_len") size_t mini_result_len(void) { return result_len; }

/* The message of the exception that escaped the last export (NUL terminated UTF-8, valid until the next failure), or NULL; clears it. */
MINI_EXPORT("mini_take_error") const char *mini_take_error(void) {
    if (!error_set) return NULL;
    error_set = false;
    return error_text;
}

/* Called by every export wrapper after the Kotlin function returned: flushes stdout and turns a pending exception into the error
 * message. True when the call failed (the result is a dummy). */
bool mk_wasm_failed(void) {
    mk_runtime_flush();
    if (mk_pending_exception == NULL) return false;
    MkObject *e = mk_catch();
    const MkString *text = e != NULL ? mk_to_string(e) : NULL;
    free(error_text);
    error_text = NULL;
    if (text != NULL) {
        size_t len;
        error_text = mk_utf16_to_utf8(text->chars, (size_t)text->length, &len);
        mk_release((MkObject *)text);
    }
    if (error_text == NULL) {
        error_text = (char *)malloc(8);
        strcpy(error_text, "error");
    }
    mk_release(e);
    error_set = true;
    return true;
}

/* A string result: consumes [s]; returns a malloc'ed UTF-8 buffer, its length in mini_result_len(). */
char *mk_wasm_string_out(MkString *s) {
    size_t len = 0;
    char *u = mk_utf16_to_utf8(s->chars, (size_t)s->length, &len);
    mk_release((MkObject *)s);
    result_len = len;
    return u;
}

/* kinds of arrays: 0 IntArray, 1 LongArray, 2 DoubleArray, 3 FloatArray */
static size_t elem_size(int kind) { return kind == 0 ? 4 : kind == 1 ? 8 : kind == 2 ? 8 : 4; }

/* An array argument: a new array holding a copy of the [length] elements at [data] (refcount 1). */
MkObject *mk_wasm_array_in(int kind, const void *data, MkInt length) {
    MkObject *a = kind == 0   ? mk_array_new_int(length)
                  : kind == 1 ? mk_array_new_long(length)
                  : kind == 2 ? mk_array_new_double(length)
                              : mk_array_new_float(length);
    if (length > 0) memcpy(MK_ARRAY_ELEMS(char, a), data, (size_t)length * elem_size(kind));
    return a;
}

/* An array result: consumes [a]; returns a malloc'ed copy of the elements, the element count in mini_result_len(). */
void *mk_wasm_array_out(MkObject *a, int kind) {
    MkInt n = ((MkArray *)a)->length;
    size_t bytes = (size_t)n * elem_size(kind);
    void *p = malloc(bytes != 0 ? bytes : 1);
    if (n > 0) memcpy(p, MK_ARRAY_ELEMS(char, a), bytes);
    mk_release(a);
    result_len = (size_t)n;
    return p;
}
