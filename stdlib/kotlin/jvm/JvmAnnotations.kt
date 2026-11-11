/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Accepted and ignored: Mini has no JVM.
 */
package kotlin.jvm

import kotlin.annotation.AnnotationTarget

@Target(AnnotationTarget.FILE)
public annotation class JvmMultifileClass

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPE)
public annotation class JvmSuppressWildcards(val suppress: Boolean = true)

@Target(AnnotationTarget.TYPE)
public annotation class JvmWildcard

@Target(AnnotationTarget.CLASS)
public annotation class JvmRecord

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
public annotation class Strictfp

@Target(AnnotationTarget.EXPRESSION)
public annotation class JvmSerializableLambda

@Target(AnnotationTarget.CLASS)
public annotation class ImplicitlyActualizedByJvmDeclaration
