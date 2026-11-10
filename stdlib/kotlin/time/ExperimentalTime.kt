/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Opt-in marker of kotlin.time (Clock, Instant). Mini does not require the opt-in, the annotation only exists so that
 * `@OptIn(ExperimentalTime::class)` compiles.
 */
package kotlin.time

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class ExperimentalTime
