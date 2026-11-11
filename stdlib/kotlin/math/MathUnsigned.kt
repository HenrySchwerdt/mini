/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin-stdlib kotlin/UMath.kt, and sign(Float).
 */
package kotlin.math

public inline fun min(a: UInt, b: UInt): UInt {
    return minOf(a, b)
}

public inline fun min(a: ULong, b: ULong): ULong {
    return minOf(a, b)
}

public inline fun max(a: UInt, b: UInt): UInt {
    return maxOf(a, b)
}

public inline fun max(a: ULong, b: ULong): ULong {
    return maxOf(a, b)
}

public fun sign(x: Float): Float = x.sign
