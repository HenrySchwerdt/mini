/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Builtin primitive arrays (members implemented natively; see docs/stdlib.md).
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT")
@file:kotlin.internal.Builtin

package kotlin

public class ByteArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Byte) : this(size)
    public operator fun get(index: Int): Byte
    public operator fun set(index: Int, value: Byte): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.ByteIterator
}

public class ShortArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Short) : this(size)
    public operator fun get(index: Int): Short
    public operator fun set(index: Int, value: Short): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.ShortIterator
}

public class IntArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Int) : this(size)
    public operator fun get(index: Int): Int
    public operator fun set(index: Int, value: Int): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.IntIterator
}

public class LongArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Long) : this(size)
    public operator fun get(index: Int): Long
    public operator fun set(index: Int, value: Long): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.LongIterator
}

public class FloatArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Float) : this(size)
    public operator fun get(index: Int): Float
    public operator fun set(index: Int, value: Float): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.FloatIterator
}

public class DoubleArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Double) : this(size)
    public operator fun get(index: Int): Double
    public operator fun set(index: Int, value: Double): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.DoubleIterator
}

public class CharArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Char) : this(size)
    public operator fun get(index: Int): Char
    public operator fun set(index: Int, value: Char): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.CharIterator
}

public class BooleanArray(size: Int) {
    public constructor(size: Int, init: (Int) -> Boolean) : this(size)
    public operator fun get(index: Int): Boolean
    public operator fun set(index: Int, value: Boolean): Unit
    public val size: Int
    public operator fun iterator(): kotlin.collections.BooleanIterator
}
