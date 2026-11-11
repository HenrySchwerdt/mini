/*
 * Ported from kotlin-stdlib reflect/PropertyReferenceDelegates.kt: a property reference is a property delegate
 * (`val x by ::other`).
 */
package kotlin

import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1

public operator fun <V> KProperty0<V>.getValue(thisRef: Any?, property: Any?): V = get()

public operator fun <V> KMutableProperty0<V>.setValue(thisRef: Any?, property: Any?, value: V) {
    set(value)
}

public operator fun <T, V> KProperty1<T, V>.getValue(thisRef: T, property: Any?): V = get(thisRef)

public operator fun <T, V> KMutableProperty1<T, V>.setValue(thisRef: T, property: KProperty<*>, value: V) {
    set(thisRef, value)
}
