#include <unity/unity.h>
#include "mini/project.h"
#include <string.h>
void setUp(void) {}
void tearDown(void) {}
static void check(const char *text, const char *error) {
    MiniSource source = {"lambda.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    if (error) {
        TEST_ASSERT_FALSE_MESSAGE(r.c_source, "invalid lambda accepted");
        TEST_ASSERT_NOT_NULL(r.message);
        TEST_ASSERT_NOT_NULL_MESSAGE(strstr(r.message, error), r.message);
    } else
        TEST_ASSERT_NOT_NULL_MESSAGE(r.c_source, r.message);
    mini_compile_result_destroy(&r);
}
static void test_typed_lambda(void) {
    check("class Main { static main():i32 { val f = { x:i32 -> x + 1 }\nreturn f(41) } }", NULL);
}
static void test_contextual_lambda(void) {
    check("class Main { static main():i32 { val f:(i32)->i32 = { x -> x + 1 }\nreturn f(41) } }",
          NULL);
}
static void test_implicit_it(void) {
    check("class Main { static main():i32 { val f:(i32)->i32 = { it + 1 }\nreturn f(41) } }", NULL);
}
static void test_zero_arguments(void) {
    check("class Main { static main():i32 { val f = { 42 }\nreturn f() } }", NULL);
}
static void test_void_lambda(void) {
    check("class Main { static main() { val f:()->void = { print_i32(42) }\nf() } }", NULL);
}
static void test_capture_val(void) {
    check("class Main { static main():i32 { val offset = 1\nval f = { x:i32 -> x + offset "
          "}\nreturn f(41) } }",
          NULL);
}
static void test_capture_var(void) {
    check("class Main { static main():i32 { var offset = 1\nval f = { x:i32 -> x + offset "
          "}\nreturn f(41) } }",
          "capture");
}
static void test_capture_uninitialized(void) {
    check("class Main { static main() { val x:i32\nval f = { x } } }", "uninitialized");
}
static void test_explicit_pointer_capture(void) {
    check("class Main { static main():i32 { var n = 41\nval p = &n\nval f = { *p = *p + 1 "
          "}\nf()\nreturn n } }",
          NULL);
}
static void test_callback_parameter(void) {
    check("class Main { static apply(value:i32, f:(i32)->i32):i32 = f(value)\n"
          "static main():i32 = Main.apply(41, { x -> x + 1 }) }",
          NULL);
}
static void test_trailing_lambda(void) {
    check("class Main { static apply(value:i32, f:(i32)->i32):i32 = f(value)\n"
          "static main():i32 = Main.apply(41) { it + 1 } }",
          NULL);
}
static void test_returned_closure(void) {
    check("class Main { static adder(n:i32):(i32)->i32 = { x -> x + n }\n"
          "static main():i32 = Main.adder(1)(41) }",
          NULL);
}
static void test_multistatement(void) {
    check("class Main { static main():i32 { val f = { x:i32 ->\nval answer = x + "
          "1\nanswer\n}\nreturn f(41) } }",
          NULL);
}
static void test_lambda_returns(void) {
    check("class Main { static main():i32 { val f:(bool)->i32 = { x -> if x { return 42 } else { "
          "return 0 } }\nreturn f(true) } }",
          NULL);
}
static void test_wrong_argument(void) {
    check("class Main { static main() { val f = { x:i32 -> x }\nf(true) } }", "type mismatch");
}
static void test_wrong_result(void) {
    check("class Main { static main() { val f:(i32)->bool = { x -> x + 1 } } }", "type mismatch");
}
static void test_wrong_arity(void) {
    check("class Main { static main() { val f:(i32)->i32 = { a,b -> a } } }", "parameter");
}
static void test_missing_parameter_type(void) {
    check("class Main { static main() { val f = { x -> x } } }", "parameter type");
}
static void test_duplicate_parameters(void) {
    check("class Main { static main() { val f = { x:i32,x:i32 -> x } } }", "duplicate");
}
static void test_field_callback(void) {
    check("class Holder { val callback:(i32)->i32 } class Main { static main():i32 { "
          "val h = Holder { callback: { x -> x + 1 } }\nreturn h.callback(41) } }",
          NULL);
}
static void test_contextual_result(void) {
    check("class Main { static main() { val f:()->Result<i32,bool> = { Result.Ok(42) }\nval result "
          "= f() } }",
          NULL);
}
static void test_generic_callback(void) {
    check("class Main { static apply<T>(value:T, f:(T)->T):T = f(value)\n"
          "static main():i32 = Main.apply<i32>(41, { it + 1 }) }",
          NULL);
}
static void test_capture_object_requires_pointer(void) {
    check("class A {} class Main { static main() { val a = A {}\nval f = { a } } }", "capture");
}
static void test_direct_invocation(void) {
    check("class Main { static main():i32 = ({ x:i32 -> x + 1 })(41) }", NULL);
}
static void test_nested_lambda(void) {
    check("class Main { static main():i32 { val f:(i32)->(i32)->i32 = { a -> { b -> a+b } "
          "}\nreturn f(40)(2) } }",
          NULL);
}
static void test_uninitialized_callback(void) {
    check("class Main { static main():i32 { val f:()->i32\nreturn f() } }", "uninitialized");
}
static void test_function_capture(void) {
    check("class Main { static main() { val f = { 42 }\nval g = { f() } } }", "capture");
}
static void test_function_signature_mismatch(void) {
    check("class Main { static main() { val f = { x:i32 -> x }\nval g:(bool)->bool = f } }",
          "type mismatch");
}
static void test_void_parameter(void) {
    check("class Main { static use(f:(void)->i32) {} }", "void");
}
static void test_missing_return(void) {
    check("class Main { static main() { val f:()->i32 = {} } }", "return");
}
static void test_lambda_local_return(void) {
    check("class Main { static main() { val f = { return 42 } } }", "contextual");
}
static void test_this_capture(void) {
    check("class A { val n:i32\ncallback():()->i32 = { this.n } }", NULL);
}
static void test_capture_is_immutable(void) {
    check("class Main { static main() { val n = 0\nval f = { n = 1 } } }", "val");
}
static void test_optional_callback(void) {
    check("class Main { static main():i32 { val f:Optional<(i32)->i32> = Optional.Some({ it+1 })\n"
          "return match f { Some(action) => action(41), None => 0 } } }",
          NULL);
}
static void test_static_super_lambda(void) {
    check("class A { static bad() { val f = { super.bad() } } }", "instance receiver");
}
int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_typed_lambda);
    RUN_TEST(test_contextual_lambda);
    RUN_TEST(test_implicit_it);
    RUN_TEST(test_zero_arguments);
    RUN_TEST(test_void_lambda);
    RUN_TEST(test_capture_val);
    RUN_TEST(test_capture_var);
    RUN_TEST(test_capture_uninitialized);
    RUN_TEST(test_explicit_pointer_capture);
    RUN_TEST(test_callback_parameter);
    RUN_TEST(test_trailing_lambda);
    RUN_TEST(test_returned_closure);
    RUN_TEST(test_multistatement);
    RUN_TEST(test_lambda_returns);
    RUN_TEST(test_wrong_argument);
    RUN_TEST(test_wrong_result);
    RUN_TEST(test_wrong_arity);
    RUN_TEST(test_missing_parameter_type);
    RUN_TEST(test_duplicate_parameters);
    RUN_TEST(test_field_callback);
    RUN_TEST(test_contextual_result);
    RUN_TEST(test_generic_callback);
    RUN_TEST(test_capture_object_requires_pointer);
    RUN_TEST(test_direct_invocation);
    RUN_TEST(test_nested_lambda);
    RUN_TEST(test_uninitialized_callback);
    RUN_TEST(test_function_capture);
    RUN_TEST(test_function_signature_mismatch);
    RUN_TEST(test_void_parameter);
    RUN_TEST(test_missing_return);
    RUN_TEST(test_lambda_local_return);
    RUN_TEST(test_this_capture);
    RUN_TEST(test_capture_is_immutable);
    RUN_TEST(test_optional_callback);
    RUN_TEST(test_static_super_lambda);
    return UNITY_END();
}
