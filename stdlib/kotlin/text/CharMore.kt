/*
 * Mini stdlib: more Char helpers (digitToIntOrNull, Char(code)).
 */
package kotlin.text

public fun Char.digitToIntOrNull(): Int? {
    if (this in '0'..'9') return this.code - '0'.code
    return null
}

public fun Char.digitToIntOrNull(radix: Int): Int? {
    val d = if (this in '0'..'9') this.code - '0'.code
    else if (this in 'a'..'z') this.code - 'a'.code + 10
    else if (this in 'A'..'Z') this.code - 'A'.code + 10
    else -1
    if (d < 0 || d >= radix) return null
    return d
}
