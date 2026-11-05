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
public annotation class Deprecated(val message: String, val replaceWith: ReplaceWith = ReplaceWith(""), val level: DeprecationLevel = DeprecationLevel.WARNING)

@Target()
public annotation class ReplaceWith(val expression: String, vararg val imports: String)

@Target(CLASS, FUNCTION, CONSTRUCTOR, PROPERTY_GETTER, PROPERTY_SETTER)
public annotation class Throws(vararg val exceptionClasses: kotlin.reflect.KClass<out Throwable>)

@Target(CLASS, PROPERTY, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, EXPRESSION, FILE, TYPEALIAS)
public annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<out Annotation>)

@Target(ANNOTATION_CLASS)
public annotation class RequiresOptIn(val message: String = "")

@Target(CLASS, PROPERTY, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, TYPEALIAS)
public annotation class SinceKotlin(val version: String)

@Target(CLASS, PROPERTY, LOCAL_VARIABLE, VALUE_PARAMETER, CONSTRUCTOR, FUNCTION, PROPERTY_GETTER, PROPERTY_SETTER, FIELD, TYPE)
public annotation class UnsafeVariance

@Target(CLASS, FUNCTION, PROPERTY, CONSTRUCTOR, PROPERTY_GETTER, PROPERTY_SETTER)
public annotation class PublishedApi

public enum class DeprecationLevel { WARNING, ERROR, HIDDEN }

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
public annotation class DeprecatedSinceKotlin(val warningSince: String = "", val errorSince: String = "", val hiddenSince: String = "")

/** Lets `sumOf { ... }` style overloads that differ only in the lambda's return type be resolved by that type. */
@Target(FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OverloadResolutionByLambdaReturnType

/** Opt-in marker of standard library APIs that may change. */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(CLASS, FUNCTION, PROPERTY, TYPEALIAS)
public annotation class ExperimentalStdlibApi

@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(CLASS, FUNCTION, PROPERTY, TYPEALIAS)
public annotation class ExperimentalUnsignedTypes
