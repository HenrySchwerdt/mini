/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 */
package kotlin

public open class Throwable(open val message: String?, open val cause: Throwable?) {
    public constructor() : this(null, null)
    public constructor(message: String?) : this(message, null)
    public constructor(cause: Throwable?) : this(cause?.toString(), cause)

    public override fun toString(): String {
        val name = mini.internal.typeName(this)
        return if (message != null) "$name: $message" else "$name"
    }
}

public open class Exception : Throwable {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class RuntimeException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class Error : Throwable {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class IllegalArgumentException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class IllegalStateException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class UnsupportedOperationException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class IndexOutOfBoundsException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class NoSuchElementException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class NumberFormatException : IllegalArgumentException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class ArithmeticException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class ClassCastException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public open class NullPointerException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public class NotImplementedError(message: String = "An operation is not implemented.") : Error(message)
