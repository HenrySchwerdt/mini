/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Subset of kotlin.math written in Kotlin (no libm yet).
 */
package kotlin.math

public fun abs(n: Int): Int = if (n < 0) -n else n

public fun abs(n: Long): Long = if (n < 0) -n else n

public fun abs(x: Double): Double = if (x < 0.0) -x else if (x == 0.0) 0.0 else x

public fun max(a: Int, b: Int): Int = if (a >= b) a else b

public fun max(a: Long, b: Long): Long = if (a >= b) a else b

public fun min(a: Int, b: Int): Int = if (a <= b) a else b

public fun min(a: Long, b: Long): Long = if (a <= b) a else b

public val Int.sign: Int
    get() = if (this < 0) -1 else if (this > 0) 1 else 0
