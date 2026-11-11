/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.random

public fun Random.nextUBytes(size: Int): UByteArray = nextBytes(size).asUByteArray()

public fun Random.nextUBytes(array: UByteArray): UByteArray {
    nextBytes(array.asByteArray())
    return array
}

public fun Random.nextUBytes(array: UByteArray, fromIndex: Int = 0, toIndex: Int = array.size): UByteArray {
    nextBytes(array.asByteArray(), fromIndex, toIndex)
    return array
}
