/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY", "MUST_BE_INITIALIZED_OR_BE_ABSTRACT")
@file:kotlin.internal.Builtin

package kotlin.collections

public abstract class IntIterator : Iterator<Int> {
    final override fun next(): Int
    public abstract fun nextInt(): Int
}

public abstract class LongIterator : Iterator<Long> {
    final override fun next(): Long
    public abstract fun nextLong(): Long
}

public abstract class CharIterator : Iterator<Char> {
    final override fun next(): Char
    public abstract fun nextChar(): Char
}

public abstract class ByteIterator : Iterator<Byte> {
    final override fun next(): Byte
    public abstract fun nextByte(): Byte
}

public abstract class ShortIterator : Iterator<Short> {
    final override fun next(): Short
    public abstract fun nextShort(): Short
}

public abstract class FloatIterator : Iterator<Float> {
    final override fun next(): Float
    public abstract fun nextFloat(): Float
}

public abstract class DoubleIterator : Iterator<Double> {
    final override fun next(): Double
    public abstract fun nextDouble(): Double
}

public abstract class BooleanIterator : Iterator<Boolean> {
    final override fun next(): Boolean
    public abstract fun nextBoolean(): Boolean
}
