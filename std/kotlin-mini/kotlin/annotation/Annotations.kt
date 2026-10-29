/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 */
@file:kotlin.internal.Builtin

package kotlin.annotation

public enum class AnnotationTarget {
    CLASS, ANNOTATION_CLASS, TYPE_PARAMETER, PROPERTY, FIELD, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR,
    FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, TYPE, EXPRESSION, FILE, TYPEALIAS
}

public enum class AnnotationRetention { SOURCE, BINARY, RUNTIME }

@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class Target(vararg val allowedTargets: AnnotationTarget)

@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class Retention(val value: AnnotationRetention = AnnotationRetention.RUNTIME)

@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class Repeatable

@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class MustBeDocumented
