/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib Comparator.kt / comparisons.
 */
package kotlin

public fun interface Comparator<T> {
    public fun compare(a: T, b: T): Int
}
