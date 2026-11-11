/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin

@Deprecated("This exception type is not thrown in Kotlin; NullPointerException is thrown instead.", ReplaceWith("NullPointerException"))
public class KotlinNullPointerException : NullPointerException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class NoWhenBranchMatchedException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

@Deprecated("This exception type is not thrown in Kotlin; ClassCastException is thrown instead.", ReplaceWith("ClassCastException"))
public open class TypeCastException : ClassCastException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}
