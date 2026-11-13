/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reduced from kotlin-stdlib text/StringBuilder.kt; the storage is a native buffer (mini.internal.sb*).
 */
package kotlin.text

import mini.internal.sbAppendChar
import mini.internal.sbAppendInt
import mini.internal.sbAppendLong
import mini.internal.sbAppendString
import mini.internal.sbCharAt
import mini.internal.sbLength
import mini.internal.sbNew
import mini.internal.sbSetCharAt
import mini.internal.sbSetLength
import mini.internal.sbToString

/**
 * A CharSequence: the compiler adds the supertype (Sema.ensureSupertypes), so that this file builds with a compiler that does not
 * know it; length / get / subSequence implement the interface. Values of that type are a String or an object of a class that
 * implements the interface (runtime mk_cs_*). Everything else follows kotlin.text.StringBuilder.
 */
public class StringBuilder : Appendable {
    private val buf: Any = sbNew()

    public constructor()

    public constructor(capacity: Int)

    public constructor(content: String) {
        sbAppendString(buf, content)
    }

    public override val length: Int
        get() = sbLength(buf)

    public override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = substring(startIndex, endIndex)

    public override operator fun get(index: Int): Char {
        if (index < 0 || index >= length) mini.internal.throwIndexOutOfLength(index, length)
        return sbCharAt(buf, index)
    }

    public operator fun set(index: Int, value: Char) {
        if (index < 0 || index >= length) mini.internal.throwIndexOutOfLength(index, length)
        sbSetCharAt(buf, index, value)
    }

    public fun substring(startIndex: Int, endIndex: Int): String = toString().substring(startIndex, endIndex)

    public fun substring(startIndex: Int): String = toString().substring(startIndex)

    public override fun append(value: Char): StringBuilder {
        sbAppendChar(buf, value)
        return this
    }

    // one overload for String and CharSequence (both mangle to the same C symbol). A String argument takes the short path, which is small enough to be
    // inlined into the caller; the rest is in appendOther
    public override fun append(value: CharSequence?): StringBuilder {
        // if / else, so that every return is a tail return (the inliner expands only such bodies)
        if (value is String) {
            sbAppendString(buf, value)
            return this
        } else {
            return appendOther(value)
        }
    }

    private fun appendOther(value: CharSequence?): StringBuilder {
        sbAppendString(buf, if (value == null) "null" else value.toString())
        return this
    }

    public fun append(value: Any?): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: Int): StringBuilder {
        sbAppendInt(buf, value)
        return this
    }

    public fun append(value: Long): StringBuilder {
        sbAppendLong(buf, value)
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
        if (index < 0 || index > length) mini.internal.throwIndexOutOfLength(index, length)
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
        if (startIndex < 0 || startIndex > end) mini.internal.throwIndexOutOfBounds("start: $startIndex, end: $endIndex, length: $len")
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
        if (newLength < 0) mini.internal.throwIllegalArgument("Negative new length: $newLength.")
        sbSetLength(buf, newLength)
    }

    public fun clear(): StringBuilder {
        sbSetLength(buf, 0)
        return this
    }

    public fun isEmpty(): Boolean = length == 0

    public fun isNotEmpty(): Boolean = length != 0

    public fun append(value: Float): StringBuilder {
        sbAppendString(buf, value.toString())
        return this
    }

    public fun append(value: Byte): StringBuilder = append(value.toInt())

    public fun append(value: Short): StringBuilder = append(value.toInt())

    public fun append(value: CharArray): StringBuilder {
        var i = 0
        while (i < value.size) {
            sbAppendChar(buf, value[i])
            i++
        }
        return this
    }

    /** `append(value, startIndex, endIndex)`: appends the characters of [value] in [startIndex, endIndex). */
    public override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): StringBuilder {
        val s = (value ?: "null").toString()
        if (startIndex < 0 || startIndex > endIndex || endIndex > s.length) {
            mini.internal.throwIndexOutOfBounds("start " + startIndex + ", end " + endIndex + ", length " + s.length)
        }
        sbAppendString(buf, s.substring(startIndex, endIndex))
        return this
    }

    public fun appendLine(value: Long): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Double): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Float): StringBuilder = append(value).append('\n')

    public fun appendLine(value: Boolean): StringBuilder = append(value).append('\n')

    public fun appendLine(value: CharArray): StringBuilder = append(value).append('\n')

    public fun insert(index: Int, value: Any?): StringBuilder = insert(index, value.toString())

    public fun insert(index: Int, value: Int): StringBuilder = insert(index, value.toString())

    public fun insert(index: Int, value: Long): StringBuilder = insert(index, value.toString())

    public fun insert(index: Int, value: Double): StringBuilder = insert(index, value.toString())

    public fun insert(index: Int, value: Boolean): StringBuilder = insert(index, value.toString())

    public fun deleteCharAt(index: Int): StringBuilder {
        if (index < 0 || index >= length) mini.internal.throwIndexOutOfLength(index, length)
        return delete(index, index + 1)
    }

    public fun replace(startIndex: Int, endIndex: Int, value: String): StringBuilder {
        val len = length
        if (startIndex < 0 || startIndex > len || startIndex > endIndex) {
            mini.internal.throwIndexOutOfBounds("start: " + startIndex + ", end: " + endIndex + ", length: " + len)
        }
        val end = if (endIndex > len) len else endIndex
        val s = toString()
        clear()
        sbAppendString(buf, s.substring(0, startIndex))
        sbAppendString(buf, value)
        sbAppendString(buf, s.substring(end))
        return this
    }

    public fun setRange(startIndex: Int, endIndex: Int, value: String): StringBuilder = replace(startIndex, endIndex, value)

    public fun deleteRange(startIndex: Int, endIndex: Int): StringBuilder = delete(startIndex, endIndex)

    public fun indexOf(string: String): Int = toString().indexOf(string)

    public fun indexOf(string: String, fromIndex: Int): Int = toString().indexOf(string, fromIndex)

    public fun lastIndexOf(string: String): Int = toString().lastIndexOf(string)

    public fun lastIndexOf(string: String, fromIndex: Int): Int = toString().lastIndexOf(string, fromIndex)

    public fun capacity(): Int = if (length < 16) 16 else length

    public fun ensureCapacity(minimumCapacity: Int) {}

    public fun trimToSize() {}

    public fun setCharAt(index: Int, value: Char) { set(index, value) }

    public fun contentEquals(other: String): Boolean = toString() == other

    public fun contentEquals(other: StringBuilder): Boolean = toString() == other.toString()

    public fun toCharArray(): CharArray = toString().toCharArray()

    override fun toString(): String = sbToString(buf)
}

public fun StringBuilder.append(vararg value: String?): StringBuilder {
    for (v in value) append(v)
    return this
}

public fun StringBuilder.insertRange(index: Int, value: String, startIndex: Int, endIndex: Int): StringBuilder =
    insert(index, value.substring(startIndex, endIndex))

public inline fun StringBuilder.appendLine(vararg value: String?): StringBuilder {
    for (v in value) appendLine(v)
    return this
}

public fun StringBuilder.substring(range: IntRange): String = substring(range.start, range.endInclusive + 1)

public inline fun StringBuilder.forEach(action: (Char) -> Unit) {
    var i = 0
    while (i < length) { action(this[i]); i++ }
}

public fun StringBuilder.first(): Char = this[0]

public fun StringBuilder.last(): Char = this[length - 1]

public val StringBuilder.lastIndex: Int
    get() = length - 1

public val StringBuilder.indices: IntRange
    get() = IntRange(0, length - 1)


public inline fun buildString(builderAction: StringBuilder.() -> Unit): String {
    val sb = StringBuilder()
    sb.builderAction()
    return sb.toString()
}

public inline fun buildString(capacity: Int, builderAction: StringBuilder.() -> Unit): String = buildString(builderAction)

public fun StringBuilder.appendRange(value: String, startIndex: Int, endIndex: Int): StringBuilder {
    var i = startIndex
    while (i < endIndex) {
        append(value[i])
        i++
    }
    return this
}
