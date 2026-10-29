#include <unity/unity.h>
#include "mini_runtime.h"

#include <string.h>

void setUp(void) {}
void tearDown(void) {}

static void test_allocate_and_free(void) {
    unsigned char *bytes = mini_alloc(16);
    TEST_ASSERT_NOT_NULL(bytes);
    memset(bytes, 42, 16);
    TEST_ASSERT_EQUAL_UINT8(42, bytes[15]);
    mini_free(bytes);
}

static void test_realloc_preserves_contents(void) {
    unsigned char *bytes = mini_alloc(8);
    TEST_ASSERT_NOT_NULL(bytes);
    memset(bytes, 23, 8);

    unsigned char *grown = mini_realloc(bytes, 32);
    if (!grown) {
        mini_free(bytes);
        TEST_FAIL_MESSAGE("allocation failed");
        return;
    }
    for (size_t i = 0; i < 8; i++)
        TEST_ASSERT_EQUAL_UINT8(23, grown[i]);
    mini_free(grown);
}

static void test_calloc_zero_initializes(void) {
    unsigned char *bytes = mini_calloc(8, sizeof(*bytes));
    TEST_ASSERT_NOT_NULL(bytes);
    for (size_t i = 0; i < 8; i++)
        TEST_ASSERT_EQUAL_UINT8(0, bytes[i]);
    mini_free(bytes);
}

static void test_free_null(void) {
    mini_free(NULL);
}

int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_allocate_and_free);
    RUN_TEST(test_realloc_preserves_contents);
    RUN_TEST(test_calloc_zero_initializes);
    RUN_TEST(test_free_null);
    return UNITY_END();
}
