/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.sequences

import kotlin.random.Random

public fun <T> Sequence<T>.shuffled(random: Random): Sequence<T> = LazyListSequence { this.toList().shuffled(random) }
