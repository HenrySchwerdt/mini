/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Character classification and case mapping: ASCII inline, the rest of the Basic Multilingual Plane from UnicodeTables.kt
 * (the semantics of java.lang.Character: simple case mappings, Other_Uppercase / Other_Lowercase).
 */
package kotlin.text

public fun Char.isDigit(): Boolean = if (this.code < 0x80) this in '0'..'9' else charType(this.code) == 9

public fun Char.isLetter(): Boolean {
    if (this.code < 0x80) return this in 'a'..'z' || this in 'A'..'Z'
    val t = charType(this.code)
    return t >= 1 && t <= 5
}

public fun Char.isLetterOrDigit(): Boolean {
    if (this.code < 0x80) return this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    val t = charType(this.code)
    return (t >= 1 && t <= 5) || t == 9
}

public fun Char.isWhitespace(): Boolean {
    val c = this.code
    if (c < 0x80) return c == 32 || (c >= 9 && c <= 13) || (c >= 0x1C && c <= 0x1F)
    val t = charType(c)
    return t >= 12 && t <= 14
}

public fun Char.isUpperCase(): Boolean {
    if (this.code < 0x80) return this in 'A'..'Z'
    return charType(this.code) == 1 || isOtherUpperCase(this.code)
}

public fun Char.isLowerCase(): Boolean {
    if (this.code < 0x80) return this in 'a'..'z'
    return charType(this.code) == 2 || isOtherLowerCase(this.code)
}

public fun Char.uppercaseChar(): Char {
    if (this.code < 0x80) return if (this in 'a'..'z') (this.code - 32).toChar() else this
    return simpleUpperCase(this.code).toChar()
}

public fun Char.lowercaseChar(): Char {
    if (this.code < 0x80) return if (this in 'A'..'Z') (this.code + 32).toChar() else this
    return simpleLowerCase(this.code).toChar()
}

public fun Char.uppercase(): String {
    if (this.code < 0x80) return uppercaseChar().toString()
    return fullUpperCase(this.code) ?: uppercaseChar().toString()
}

public fun Char.lowercase(): String = if (this.code == 0x130) "i\u0307" else lowercaseChar().toString()

/** The value of the character as a digit in [radix] (decimal digits of any script, Latin letters, full width letters), else -1. */
internal fun digitValueOf(ch: Char, radix: Int): Int {
    val code = ch.code
    val d = if (code >= 48 && code <= 57) code - 48
    else if (code >= 97 && code <= 122) code - 87
    else if (code >= 65 && code <= 90) code - 55
    else if (code < 0x80) -1
    else if (charType(code) == 9) unicodeDigitValue(code)
    else if (code >= 0xFF21 && code <= 0xFF3A) code - 0xFF21 + 10
    else if (code >= 0xFF41 && code <= 0xFF5A) code - 0xFF41 + 10
    else -1
    return if (d >= 0 && d < radix) d else -1
}

public fun Char.digitToInt(): Int {
    val d = digitValueOf(this, 10)
    if (d < 0) mini.internal.throwIllegalArgument("Char $this is not a decimal digit")
    return d
}

public fun Char.digitToInt(radix: Int): Int {
    val d = digitValueOf(this, radix)
    if (d < 0) mini.internal.throwIllegalArgument("Char $this is not a digit in the radix $radix")
    return d
}

public fun Int.digitToChar(): Char {
    if (this in 0..9) return ('0'.code + this).toChar()
    mini.internal.throwIllegalArgument("Int $this is not a decimal digit")
}

public fun Int.digitToChar(radix: Int): Char {
    if (this < 0 || this >= radix) mini.internal.throwIllegalArgument("Digit $this does not represent a valid digit in radix $radix")
    return if (this < 10) ('0'.code + this).toChar() else ('A'.code + this - 10).toChar()
}

public fun Char.equals(other: Char, ignoreCase: Boolean = false): Boolean {
    if (this == other) return true
    if (!ignoreCase) return false
    val thisUpper = this.uppercaseChar()
    val otherUpper = other.uppercaseChar()
    return thisUpper == otherUpper || thisUpper.lowercaseChar() == otherUpper.lowercaseChar()
}
