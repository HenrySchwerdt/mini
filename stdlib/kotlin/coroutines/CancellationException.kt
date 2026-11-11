/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.coroutines.cancellation

public open class CancellationException : IllegalStateException {
    public constructor() : super()
    public constructor(message: String?) : super(message)

    // the Throwable of Mini keeps its cause in the object header, so it is given at construction (the public factories below)
    internal constructor(message: String?, cause: Throwable?, withCause: Boolean) : super(message, cause)
}

public fun CancellationException(message: String?, cause: Throwable?): CancellationException =
    CancellationException(message, cause, true)

public fun CancellationException(cause: Throwable?): CancellationException =
    CancellationException(cause?.toString(), cause, true)
