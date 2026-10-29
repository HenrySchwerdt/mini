package mkt.test

/*
 * Test helpers of the stdlib unit tests. The tests are plain Kotlin: every top-level function whose name
 * starts with `test` is run by compiler/jvm-bridge StdlibJvmTest. The same sources are also compiled against
 * the real kotlin-stdlib (`import mkt.X` -> `import kotlin.X`) to prove the expectations are real Kotlin behaviour.
 */

fun eq(expected: Any?, actual: Any?, what: String = "") {
    if (expected != actual) throw AssertionError("$what: expected <$expected> but was <$actual>")
}

fun isTrue(actual: Boolean, what: String = "") {
    if (!actual) throw AssertionError("$what: expected true")
}

fun isFalse(actual: Boolean, what: String = "") {
    if (actual) throw AssertionError("$what: expected false")
}

fun throwsA(name: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        var c: Class<*>? = t.javaClass
        while (c != null) {
            if (c.simpleName == name) return
            c = c.superclass
        }
        throw AssertionError("expected $name but got $t")
    }
    throw AssertionError("expected $name but nothing was thrown")
}
