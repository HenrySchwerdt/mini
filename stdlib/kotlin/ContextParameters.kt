/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib ContextParameters.kt (context parameters, Kotlin 2.2+; contracts removed).
 */
package kotlin

/** Runs [block] with [with] available as a context argument (`context(arena) { ... }`). */
public inline fun <A, R> context(with: A, block: context(A) () -> R): R = block(with)

public inline fun <A, B, R> context(a: A, b: B, block: context(A, B) () -> R): R = block(a, b)

public inline fun <A, B, C, R> context(a: A, b: B, c: C, block: context(A, B, C) () -> R): R = block(a, b, c)

/** Returns the context argument of type [A] in scope. */
context(value: A)
public fun <A> contextOf(): A = value
