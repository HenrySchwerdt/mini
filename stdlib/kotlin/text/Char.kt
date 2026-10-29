/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Character classification: ASCII and Latin-1 only in v1 (docs/stdlib.md, limitations).
 */
package kotlin.text

public fun Char.isDigit(): Boolean = this in '0'..'9'

public fun Char.isLetter(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || (this.code >= 0xC0 && this.code != 0xD7 && this.code != 0xF7 && this.code <= 0x24F)

public fun Char.isLetterOrDigit(): Boolean = isLetter() || isDigit()

public fun Char.isWhitespace(): Boolean =
    this == ' ' || this == '\t' || this == '\n' || this == '\r' || this.code == 0x0B || this.code == 0x0C || this.code == 0xA0

public fun Char.isUpperCase(): Boolean = this in 'A'..'Z' || (this.code in 0xC0..0xDE && this.code != 0xD7)

public fun Char.isLowerCase(): Boolean = this in 'a'..'z' || (this.code in 0xDF..0xFF && this.code != 0xF7)

public fun Char.uppercaseChar(): Char =
    if (this in 'a'..'z' || (this.code in 0xE0..0xFE && this.code != 0xF7)) (this.code - 32).toChar() else this

public fun Char.lowercaseChar(): Char =
    if (this in 'A'..'Z' || (this.code in 0xC0..0xDE && this.code != 0xD7)) (this.code + 32).toChar() else this

public fun Char.uppercase(): String = uppercaseChar().toString()

public fun Char.lowercase(): String = lowercaseChar().toString()

public fun Char.digitToInt(): Int {
    if (this in '0'..'9') return this.code - '0'.code
    mini.internal.throwIllegalArgument("Char $this is not a decimal digit")
}

public fun Char.digitToInt(radix: Int): Int {
    val d = if (this in '0'..'9') this.code - '0'.code
    else if (this in 'a'..'z') this.code - 'a'.code + 10
    else if (this in 'A'..'Z') this.code - 'A'.code + 10
    else -1
    if (d < 0 || d >= radix) mini.internal.throwIllegalArgument("Char $this is not a digit in the radix $radix")
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

public fun Char.equals(other: Char, ignoreCase: Boolean = false): Boolean =
    this == other || (ignoreCase && this.uppercaseChar() == other.uppercaseChar())
