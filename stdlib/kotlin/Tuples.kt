/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib util/Tuples.kt (Serializable removed).
 */
package kotlin

public data class Pair<out A, out B>(
    public val first: A,
    public val second: B
) {
    public override fun toString(): String = "($first, $second)"
}

public infix fun <A, B> A.to(that: B): Pair<A, B> = Pair(this, that)

public fun <T> Pair<T, T>.toList(): List<T> = listOf(first, second)

public data class Triple<out A, out B, out C>(
    public val first: A,
    public val second: B,
    public val third: C
) {
    public override fun toString(): String = "($first, $second, $third)"
}

public fun <T> Triple<T, T, T>.toList(): List<T> = listOf(first, second, third)
