/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Reduced from kotlin-stdlib text/StringBuilder.kt; the storage is a native buffer (mini.internal.sb*).
 */
package kotlin.text

import mini.internal.sbAppendChar
import mini.internal.sbAppendString
import mini.internal.sbCharAt
import mini.internal.sbLength
import mini.internal.sbNew
import mini.internal.sbSetCharAt
import mini.internal.sbSetLength
import mini.internal.sbToString

public interface Appendable {
    public fun append(value: Char): Appendable
    public fun append(value: CharSequence?): Appendable
}

public class StringBuilder : Appendable, CharSequence {
    private val buf: Any = sbNew()

    public constructor()

    public constructor(capacity: Int)

    public constructor(content: String) {
        sbAppendString(buf, content)
    }

    override val length: Int
        get() = sbLength(buf)

    override fun get(index: Int): Char {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("index: $index, length: $length")
        return sbCharAt(buf, index)
    }

    public operator fun set(index: Int, value: Char) {
        if (index < 0 || index >= length) throw IndexOutOfBoundsException("index: $index, length: $length")
        sbSetCharAt(buf, index, value)
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = substring(startIndex, endIndex)

    public fun substring(startIndex: Int, endIndex: Int): String = toString().substring(startIndex, endIndex)

    public fun substring(startIndex: Int): String = toString().substring(startIndex)

    override fun append(value: Char): StringBuilder {
        sbAppendChar(buf, value)
        return this
    }

    override fun append(value: CharSequence?): StringBuilder {
        if (value == null) sbAppendString(buf, "null") else sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: String?): StringBuilder {
        sbAppendString(buf, value ?: "null")
        return this
    }

    public fun append(value: Any?): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: Int): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: Long): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: Double): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: Boolean): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun appendLine(): StringBuilder = append('\n')

    public fun appendLine(value: String?): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Char): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Any?): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Int): StringBuilder = append(value).append('\n')

    public fun insert(index: Int, value: String): StringBuilder {
        if (index < 0 || index > length) throw IndexOutOfBoundsException("index: $index, length: $length")
        val s = toString()
        clear()
        sbAppendString(buf, s.substring(0, index))
        sbAppendString(buf, value)
        sbAppendString(buf, s.substring(index))
        return this
    }

    public fun insert(index: Int, value: Char): StringBuilder = insert(index, value.toString())

    public fun deleteAt(index: Int): StringBuilder = delete(index, index + 1)

    public fun delete(startIndex: Int, endIndex: Int): StringBuilder {
        val len = length
        val end = if (endIndex > len) len else endIndex
        if (startIndex < 0 || startIndex > end) throw IndexOutOfBoundsException("start: $startIndex, end: $endIndex, length: $len")
        val s = toString()
        clear()
        sbAppendString(buf, s.substring(0, startIndex))
        sbAppendString(buf, s.substring(end))
        return this
    }

    public fun reverse(): StringBuilder {
        var i = 0
        var j = length - 1
        while (i < j) {
            val c = sbCharAt(buf, i)
            sbSetCharAt(buf, i, sbCharAt(buf, j))
            sbSetCharAt(buf, j, c)
            i++
            j--
        }
        return this
    }

    public fun setLength(newLength: Int) {
        if (newLength < 0) throw IllegalArgumentException("Negative new length: $newLength.")
        sbSetLength(buf, newLength)
    }

    public fun clear(): StringBuilder {
        sbSetLength(buf, 0)
        return this
    }

    public fun isEmpty(): Boolean = length == 0

    public fun isNotEmpty(): Boolean = length != 0

    override fun toString(): String = sbToString(buf)
}

public inline fun buildString(builderAction: StringBuilder.() -> Unit): String {
    val sb = StringBuilder()
    sb.builderAction()
    return sb.toString()
}

public inline fun buildString(capacity: Int, builderAction: StringBuilder.() -> Unit): String = buildString(builderAction)

public fun StringBuilder.appendRange(value: CharSequence, startIndex: Int, endIndex: Int): StringBuilder {
    var i = startIndex
    while (i < endIndex) {
        append(value[i])
        i++
    }
    return this
}
