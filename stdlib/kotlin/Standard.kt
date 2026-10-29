/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib util/Standard.kt (contracts removed).
 */
package kotlin

public inline fun <R> run(block: () -> R): R = block()

public inline fun <T, R> T.run(block: T.() -> R): R = block()

public inline fun <T, R> with(receiver: T, block: T.() -> R): R = receiver.block()

public inline fun <T> T.apply(block: T.() -> Unit): T {
    block()
    return this
}

public inline fun <T> T.also(block: (T) -> Unit): T {
    block(this)
    return this
}

public inline fun <T, R> T.let(block: (T) -> R): R = block(this)

public inline fun <T> T.takeIf(predicate: (T) -> Boolean): T? = if (predicate(this)) this else null

public inline fun <T> T.takeUnless(predicate: (T) -> Boolean): T? = if (!predicate(this)) this else null

public inline fun repeat(times: Int, action: (Int) -> Unit) {
    for (index in 0 until times) {
        action(index)
    }
}
