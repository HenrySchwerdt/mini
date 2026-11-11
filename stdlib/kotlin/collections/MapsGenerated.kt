/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * The long tail of the kotlin.collections functions (ported from kotlin-stdlib _Maps.kt).
 */
package kotlin.collections


@OverloadResolutionByLambdaReturnType
public inline fun <K, V, R> Map<out K, V>.maxOfWithOrNull(comparator: Comparator<in R>, selector: (Map.Entry<K, V>) -> R): R? {
    return entries.maxOfWithOrNull(comparator, selector)
}

@OverloadResolutionByLambdaReturnType
public inline fun <K, V, R> Map<out K, V>.minOfWithOrNull(comparator: Comparator<in R>, selector: (Map.Entry<K, V>) -> R): R? {
    return entries.minOfWithOrNull(comparator, selector)
}
