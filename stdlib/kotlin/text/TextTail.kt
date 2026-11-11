/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Remaining overloads of kotlin.text: Char delimiters of replaceBefore / replaceAfter, appendRange / insertRange of
 * CharSequence and CharArray, Char(UShort).
 */
package kotlin.text

public fun String.replaceBefore(delimiter: Char, replacement: String, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(0, index, replacement)
}

public fun String.replaceAfter(delimiter: Char, replacement: String, missingDelimiterValue: String = this): String {
    val index = indexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(index + 1, length, replacement)
}

public fun String.replaceBeforeLast(delimiter: Char, replacement: String, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(0, index, replacement)
}

public fun String.replaceAfterLast(delimiter: Char, replacement: String, missingDelimiterValue: String = this): String {
    val index = lastIndexOf(delimiter)
    return if (index == -1) missingDelimiterValue else replaceRange(index + 1, length, replacement)
}

public fun StringBuilder.appendRange(value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder {
    var i = startIndex
    while (i < endIndex) {
        append(value[i])
        i++
    }
    return this
}

public fun StringBuilder.appendRange(value: CharArray, startIndex: Int, endIndex: Int): StringBuilder {
    var i = startIndex
    while (i < endIndex) {
        append(value[i])
        i++
    }
    return this
}

public fun StringBuilder.insertRange(index: Int, value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder =
    insert(index, value.subSequence(startIndex, endIndex).toString())

public fun StringBuilder.insertRange(index: Int, value: CharArray, startIndex: Int, endIndex: Int): StringBuilder =
    insert(index, value.concatToString(startIndex, endIndex))

public fun Char(code: UShort): Char = code.toInt().toChar()
