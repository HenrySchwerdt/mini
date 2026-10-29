/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 */
package kotlin.internal

import kotlin.annotation.AnnotationTarget

/**
 * Marks a file whose declarations are known to the compiler and have no bodies (`kotlin.Int`, ...).
 * `minic` analyses such files with the rest of the sources but never converts them to C.
 */
@Target(AnnotationTarget.FILE)
internal annotation class Builtin
