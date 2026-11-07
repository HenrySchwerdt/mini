/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Opt-in marker of kotlin.io.encoding. Mini does not require the opt-in (the API is stable since Kotlin 2.2), the annotation only
 * exists so that `@OptIn(ExperimentalEncodingApi::class)` compiles.
 */
package kotlin.io.encoding

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class ExperimentalEncodingApi
