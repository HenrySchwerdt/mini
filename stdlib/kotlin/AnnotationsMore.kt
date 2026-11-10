/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Annotations that programs name but Mini does not interpret (accepted and ignored).
 */
package kotlin

@Target(AnnotationTarget.TYPE)
public annotation class ExtensionFunctionType

@Target(AnnotationTarget.TYPE)
public annotation class ContextFunctionTypeParams(val count: Int)

@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
public annotation class ParameterName(val name: String)

@Target(AnnotationTarget.ANNOTATION_CLASS)
public annotation class DslMarker

@Target(AnnotationTarget.CLASS, AnnotationTarget.FILE)
public annotation class Metadata(
    val kind: Int = 1,
    val metadataVersion: IntArray = [],
    val data1: Array<String> = [],
    val data2: Array<String> = [],
    val extraString: String = "",
    val packageName: String = "",
    val extraInt: Int = 0
)
