/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib util/Numbers.kt (floating point classification).
 */
package kotlin

public fun Double.isNaN(): Boolean = this != this

public fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY

public fun Double.isFinite(): Boolean = !isInfinite() && !isNaN()

public fun Float.isNaN(): Boolean = this != this

public fun Float.isInfinite(): Boolean = this == Float.POSITIVE_INFINITY || this == Float.NEGATIVE_INFINITY

public fun Float.isFinite(): Boolean = !isInfinite() && !isNaN()
