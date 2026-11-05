/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.jvm

import kotlin.annotation.AnnotationTarget

/** Accepted and ignored: Mini has no JVM; value classes are what the backend makes of them. */
@Target(AnnotationTarget.CLASS)
public annotation class JvmInline

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.PROPERTY)
public annotation class JvmStatic

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
public annotation class JvmName(val name: String)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
public annotation class JvmField

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR)
public annotation class JvmOverloads

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
public annotation class JvmSynthetic

@Target(AnnotationTarget.FIELD)
public annotation class Volatile

@Target(AnnotationTarget.FIELD)
public annotation class Transient

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
public annotation class Synchronized
