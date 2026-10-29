/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
@file:kotlin.internal.Builtin

package kotlin

import kotlin.annotation.AnnotationTarget.*

@Target(CLASS, FUNCTION, PROPERTY, FIELD, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, PROPERTY_GETTER, PROPERTY_SETTER, TYPEALIAS, EXPRESSION, FILE)
@Retention(AnnotationRetention.SOURCE)
public annotation class Suppress(vararg val names: String)

@Target(CLASS, FUNCTION, PROPERTY, ANNOTATION_CLASS, CONSTRUCTOR, PROPERTY_SETTER, PROPERTY_GETTER, TYPEALIAS)
public annotation class Deprecated(val message: String)

@Target(CLASS, PROPERTY, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, FIELD, TYPE)
public annotation class UnsafeVariance

@Target(FUNCTION, PROPERTY, PROPERTY_GETTER, PROPERTY_SETTER)
public annotation class PublishedApi

public enum class DeprecationLevel { WARNING, ERROR, HIDDEN }

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class DeprecatedSinceKotlin(val warningSince: String = "", val errorSince: String = "", val hiddenSince: String = "")

/** Lets `sumOf { ... }` style overloads that differ only in the lambda's return type be resolved by that type. */
@Target(FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OverloadResolutionByLambdaReturnType
