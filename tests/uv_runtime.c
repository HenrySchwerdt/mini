#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#endif
#include <uv.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdio.h>

/* Inject failures into the exact adapter embedded by the compiler, without
 * changing libuv's allocator or depending on machine memory exhaustion. */
static int allocation_calls, live_allocations, fail_allocation;
static bool fail_start, fail_condition;
static void *test_malloc(size_t size) {
    if (++allocation_calls == fail_allocation)
        return NULL;
    void *p = malloc(size);
    if (p)
        live_allocations++;
    return p;
}
static void test_free(void *p) {
    if (p)
        live_allocations--;
    free(p);
}
static int test_start(uv_thread_t *id, uv_thread_cb callback, void *data) {
    return fail_start ? UV_EAGAIN : uv_thread_create(id, callback, data);
}
static int test_condition(uv_cond_t *condition) {
    return fail_condition ? UV_EAGAIN : uv_cond_init(condition);
}
#define malloc test_malloc
#define free test_free
#define uv_thread_create test_start
#define uv_cond_init test_condition
#define main mini_fixture_main
#if defined(__GNUC__) || defined(__clang__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#elif defined(_MSC_VER)
#pragma warning(push)
#pragma warning(disable : 4505)
#endif
#include "mini_uv_fixture.c"
#if defined(__GNUC__) || defined(__clang__)
#pragma GCC diagnostic pop
#elif defined(_MSC_VER)
#pragma warning(pop)
#endif
#undef main
#undef uv_cond_init
#undef uv_thread_create
#undef free
#undef malloc

#define CHECK(value)                                                                               \
    do {                                                                                           \
        if (!(value)) {                                                                            \
            fprintf(stderr, "line %d: %s\n", __LINE__, #value);                                    \
            return 1;                                                                              \
        }                                                                                          \
    } while (0)
typedef struct {
    int value;
    int *output;
    uv_sem_t *gate;
    uint8_t **handle;
    uint8_t *mutex;
} Payload;
static void no_op(void *data) {
    (void)data;
}
static void copy_task(void *data) {
    Payload *p = data;
    uv_sem_wait(p->gate);
    *p->output = p->value;
}
static void self_join(void *data) {
    Payload *p = data;
    uv_sem_wait(p->gate);
    *p->output = mini_uv_join(*p->handle);
}
static void foreign_unlock(void *data) {
    Payload *p = data;
    *p->output = mini_uv_mutex_unlock(p->mutex);
}
int main(void) {
    int32_t error;
    Payload payload = {0};
    for (int failure = 1; failure <= 2; failure++) {
        allocation_calls = 0;
        fail_allocation = failure;
        CHECK(!mini_uv_start(&payload, sizeof(payload), no_op, &error));
        CHECK(error == UV_ENOMEM && live_allocations == 0);
    }
    fail_allocation = 0;
    fail_start = true;
    CHECK(!mini_uv_start(&payload, sizeof(payload), no_op, &error));
    CHECK(error == UV_EAGAIN && live_allocations == 0);
    fail_start = false;

    uv_sem_t gate;
    CHECK(uv_sem_init(&gate, 0) == 0);
    int output = 0;
    payload.value = 42;
    payload.output = &output;
    payload.gate = &gate;
    uint8_t *handle = mini_uv_start(&payload, sizeof(payload), copy_task, &error);
    CHECK(handle && error == 0 && mini_uv_alive(handle));
    payload.value = 99;
    uv_sem_post(&gate);
    CHECK(mini_uv_join(handle) == 0);
    CHECK(output == 42 && live_allocations == 0);

    payload.handle = &handle;
    handle = mini_uv_start(&payload, sizeof(payload), self_join, &error);
    CHECK(handle && error == 0);
    uv_sem_post(&gate);
    CHECK(mini_uv_join(handle) == 0);
    CHECK(output == UV_EBUSY && live_allocations == 0);
    uv_sem_destroy(&gate);

    allocation_calls = 0;
    fail_allocation = 1;
    CHECK(!mini_uv_mutex_create(&error));
    CHECK(error == UV_ENOMEM && live_allocations == 0);
    fail_allocation = 0;
    fail_condition = true;
    CHECK(!mini_uv_mutex_create(&error));
    CHECK(error == UV_EAGAIN && live_allocations == 0);
    fail_condition = false;
    uint8_t *mutex = mini_uv_mutex_create(&error);
    CHECK(mutex && error == 0 && live_allocations == 1);
    CHECK(mini_uv_mutex_unlock(mutex) == UV_EPERM);
    CHECK(mini_uv_mutex_lock(mutex) == 0);
    CHECK(mini_uv_mutex_lock(mutex) == UV_EBUSY);
    CHECK(mini_uv_mutex_trylock(mutex) == UV_EBUSY);
    CHECK(mini_uv_mutex_close(mutex) == UV_EBUSY);
    payload.mutex = mutex;
    handle = mini_uv_start(&payload, sizeof(payload), foreign_unlock, &error);
    CHECK(handle && error == 0);
    CHECK(mini_uv_join(handle) == 0);
    CHECK(output == UV_EPERM && live_allocations == 1);
    CHECK(mini_uv_mutex_unlock(mutex) == 0);
    CHECK(mini_uv_mutex_trylock(mutex) == 0);
    CHECK(mini_uv_mutex_unlock(mutex) == 0);
    CHECK(mini_uv_mutex_close(mutex) == 0 && live_allocations == 0);
    puts("libuv adapter tests passed");
    return 0;
}
