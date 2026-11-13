/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Small JVM-flavoured declarations that portable Kotlin sources (e.g. mini.memory) mention.
 * They exist so that those sources analyse without a JDK; they carry no JVM semantics.
 */
package kotlin

import mini.memory.free

public interface AutoCloseable {
    public fun close()
}

public inline fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
    var exception: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        exception = e
        throw e
    } finally {
        // Mini: `use` ends the ownership of the receiver, so it closes and frees (docs/memory-model.md 3.2); free() is close()
        // on the JVM and a no-op for reference counted objects. When the block threw, an exception of close() is added to it as suppressed.
        if (exception == null) {
            this.free()
        } else {
            try {
                this.free()
            } catch (closeException: Throwable) {
                exception.addSuppressed(closeException)
            }
        }
    }
}
