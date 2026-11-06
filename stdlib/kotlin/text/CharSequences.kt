/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib text/Strings.kt: the CharSequence receivers (a String or a StringBuilder at run time;
 * the String overloads in Strings.kt / StringsMore.kt are more specific and keep the direct paths).
 */
package kotlin.text

public val CharSequence.indices: IntRange
    get() = IntRange(0, length - 1)

public val CharSequence.lastIndex: Int
    get() = length - 1

public operator fun CharSequence.iterator(): CharIterator = CharSequenceIterator(this)

private class CharSequenceIterator(private val s: CharSequence) : CharIterator() {
    private var index = 0

    override fun nextChar(): Char = if (index < s.length) s[index++] else throw IndexOutOfBoundsException("$index")

    override fun hasNext(): Boolean = index < s.length
}

public fun CharSequence.withIndex(): Iterable<IndexedValue<Char>> = CharSequenceIndexing(this)

private class CharSequenceIndexing(private val s: CharSequence) : Iterable<IndexedValue<Char>> {
    override fun iterator(): Iterator<IndexedValue<Char>> = CharSequenceIndexIterator(s.iterator())
}

private class CharSequenceIndexIterator(private val it: CharIterator) : Iterator<IndexedValue<Char>> {
    private var index = 0
    override fun hasNext(): Boolean = it.hasNext()
    override fun next(): IndexedValue<Char> = IndexedValue(index++, it.nextChar())
}

public inline fun CharSequence.forEach(action: (Char) -> Unit) {
    for (index in 0 until length) action(get(index))
}

public inline fun CharSequence.forEachIndexed(action: (index: Int, Char) -> Unit) {
    var index = 0
    for (item in this) action(index++, item)
}
