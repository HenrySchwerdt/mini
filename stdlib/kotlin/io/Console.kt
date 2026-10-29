/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 *
 * Console output. Calls are recognised by the Mini backend and mapped to libminirt (mk_print_*,
 * mk_println_*); the declarations are `external` so they are never converted themselves.
 */
package kotlin.io

public external fun println()
public external fun println(message: Any?)
public external fun print(message: Any?)
public external fun println(message: Int)
public external fun print(message: Int)
public external fun println(message: Long)
public external fun print(message: Long)
public external fun println(message: Byte)
public external fun print(message: Byte)
public external fun println(message: Short)
public external fun print(message: Short)
public external fun println(message: Float)
public external fun print(message: Float)
public external fun println(message: Double)
public external fun print(message: Double)
public external fun println(message: Boolean)
public external fun print(message: Boolean)
public external fun println(message: Char)
public external fun print(message: Char)
