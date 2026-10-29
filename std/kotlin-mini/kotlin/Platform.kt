/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 *
 * Small JVM-flavoured declarations that portable Kotlin sources (e.g. mini.memory) mention.
 * They exist so that those sources analyse without a JDK; they carry no JVM semantics.
 */
package kotlin

public interface AutoCloseable {
    public fun close()
}

public fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
    try {
        return block(this)
    } finally {
        this?.close()
    }
}
