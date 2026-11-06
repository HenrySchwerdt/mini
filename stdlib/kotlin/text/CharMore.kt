/*
 * Mini stdlib: more Char helpers (digitToIntOrNull, Char(code)).
 */
package kotlin.text

public fun Char.digitToIntOrNull(): Int? {
    val d = digitValueOf(this, 10)
    return if (d < 0) null else d
}

public fun Char.digitToIntOrNull(radix: Int): Int? {
    val d = digitValueOf(this, radix)
    return if (d < 0) null else d
}

