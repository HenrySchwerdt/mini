/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.comparisons

public inline fun <T, K> Comparator<T>.thenByDescending(comparator: Comparator<in K>, crossinline selector: (T) -> K): Comparator<T> =
    Comparator { a, b ->
        val previousCompare = this.compare(a, b)
        if (previousCompare != 0) previousCompare else comparator.compare(selector(b), selector(a))
    }

public infix fun <T> Comparator<T>.thenDescending(comparator: Comparator<in T>): Comparator<T> =
    Comparator<T> { a, b ->
        val previousCompare = this.compare(a, b)
        if (previousCompare != 0) previousCompare else comparator.compare(b, a)
    }
