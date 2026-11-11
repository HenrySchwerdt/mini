/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.concurrent

/** Accepted and ignored: Mini fields carry no memory model attributes. */
@Target(AnnotationTarget.FIELD)
public annotation class Volatile
