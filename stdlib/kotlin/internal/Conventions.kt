/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.internal

import kotlin.annotation.AnnotationTarget

/**
 * Calling conventions of the stdlib hot paths (compiler/backend Conventions.kt, docs/benchmarks.md D2). Both are
 * requests to the code generation that never change what a program means; without them (or with `--no-conv`) the
 * default conventions apply: parameters borrowed, results owned.
 *
 * `@ReturnsBorrowed`: the result is a reference some other owner keeps alive (an element of a collection, a field of a
 * parameter); the caller does not release it and treats it like a field read. The backend checks every function of the
 * virtual / interface dispatch group and drops the request when one of them returns a value it created.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
internal annotation class ReturnsBorrowed

/**
 * `@NoHeapEffects` on a function: the function does not overwrite a heap reference that a caller's borrowed alias can
 * depend on. It may build and fill objects of its own (a copy of a collection, an iterator), so its code does contain
 * reference stores, which the backend's effect analysis could not tell from stores into the caller's objects. Without
 * this, `equals` / `hashCode` / `toString` of the collections (reached through every `==` on a generic value) would
 * count as arbitrary heap writers. An assertion by the stdlib author that the compiler does not check.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
internal annotation class NoHeapEffects

/** `@TakesOwnership` on a parameter: the callee owns the reference it is given (stores it or releases it at its exits). */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
internal annotation class TakesOwnership
