/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Reimplemented after kotlin-stdlib collections/MapAccessors.kt: `val x by map` / `var x by mutableMap`.
 */
package kotlin.collections

import kotlin.reflect.KProperty

@Suppress("UNCHECKED_CAST")
public operator fun <V, V1 : V> Map<in String, V>.getValue(thisRef: Any?, property: KProperty<*>): V1 {
    val m = this as Map<String, V>
    val key = property.name
    if (m is MapWithDefault<*, *>) return (m as MapWithDefault<String, V>).getOrImplicitDefault(key) as V1
    val v = m[key]
    if (v == null && !m.containsKey(key)) throw NoSuchElementException("Key " + key + " is missing in the map.")
    return v as V1
}

public operator fun <V> MutableMap<in String, in V>.setValue(thisRef: Any?, property: KProperty<*>, value: V) {
    this.put(property.name, value)
}
