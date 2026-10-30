package mini.internal

/*
 * JVM implementation of mini/internal/Intrinsics.kt, used only by the stdlib unit tests
 * (tools/k2-oracle StdlibJvmTest): the stdlib sources are compiled with this file instead of the
 * native one, so their logic can be tested on the JVM. Signatures must match the native file
 * (a test checks this).
 */

fun typeName(o: Any): String = o.javaClass.name

fun identityHashCode(o: Any?): Int = System.identityHashCode(o)

fun stringSubstring(s: String, start: Int, end: Int): String = (s as java.lang.String).substring(start, end)

fun stringParseDouble(s: String): Double = s.toDouble()

fun stringIsDouble(s: String): Boolean = s.toDoubleOrNull() != null

fun sbNew(): Any = java.lang.StringBuilder()

fun sbAppendString(sb: Any, s: String) {
    (sb as java.lang.StringBuilder).append(s)
}

fun sbAppendInt(sb: Any, value: Int) {
    (sb as java.lang.StringBuilder).append(value)
}

fun sbAppendLong(sb: Any, value: Long) {
    (sb as java.lang.StringBuilder).append(value)
}

fun sbAppendChar(sb: Any, c: Char) {
    (sb as java.lang.StringBuilder).append(c)
}

fun sbLength(sb: Any): Int = (sb as java.lang.StringBuilder).length

fun sbCharAt(sb: Any, index: Int): Char = (sb as java.lang.StringBuilder)[index]

fun sbSetCharAt(sb: Any, index: Int, c: Char) {
    (sb as java.lang.StringBuilder).setCharAt(index, c)
}

fun sbSetLength(sb: Any, length: Int) {
    (sb as java.lang.StringBuilder).setLength(length)
}

fun sbToString(sb: Any): String = sb.toString()

@Suppress("UNCHECKED_CAST")
fun refArrayNew(size: Int): Any = arrayOfNulls<Any?>(size)

@Suppress("UNCHECKED_CAST")
fun refArrayGet(array: Any, index: Int): Any? = (array as Array<Any?>)[index]

@Suppress("UNCHECKED_CAST")
fun refArraySet(array: Any, index: Int, value: Any?) {
    (array as Array<Any?>)[index] = value
}

@Suppress("UNCHECKED_CAST")
fun refArrayGetBorrowed(array: Any, index: Int): Any? = (array as Array<Any?>)[index]

@Suppress("UNCHECKED_CAST")
fun refArraySetOwned(array: Any, index: Int, value: Any?) {
    (array as Array<Any?>)[index] = value
}

@Suppress("UNCHECKED_CAST")
fun refArrayLength(array: Any): Int = (array as Array<Any?>).size

fun throwIllegalArgument(message: String?): Nothing = throw IllegalArgumentException(message)

fun throwIllegalState(message: String?): Nothing = throw IllegalStateException(message)

fun throwIndexOutOfBounds(message: String?): Nothing = throw IndexOutOfBoundsException(message)

fun throwNoSuchElement(message: String?): Nothing = throw NoSuchElementException(message)

fun throwNumberFormat(message: String?): Nothing = throw NumberFormatException(message)

fun throwUnsupportedOperation(message: String?): Nothing = throw UnsupportedOperationException(message)

fun throwNotImplemented(message: String?): Nothing =
    if (message == null) throw NotImplementedError() else throw NotImplementedError(message)
