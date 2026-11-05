/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib properties/Delegates.kt, ReadWriteProperty.kt, PropertyReferenceDelegates.kt.
 */
package kotlin.properties

import kotlin.reflect.KProperty

public fun interface ReadOnlyProperty<in T, out V> {
    public operator fun getValue(thisRef: T, property: KProperty<*>): V
}

public interface ReadWriteProperty<in T, V> {
    public operator fun getValue(thisRef: T, property: KProperty<*>): V
    public operator fun setValue(thisRef: T, property: KProperty<*>, value: V)
}

public fun interface PropertyDelegateProvider<in T, out D> {
    public operator fun provideDelegate(thisRef: T, property: KProperty<*>): D
}

public object Delegates {
    public fun <T : Any> notNull(): ReadWriteProperty<Any?, T> = NotNullVar()

    public fun <T> observable(initialValue: T, onChange: (property: KProperty<*>, oldValue: T, newValue: T) -> Unit): ReadWriteProperty<Any?, T> =
        ObservableProperty(initialValue, null, onChange)

    public fun <T> vetoable(initialValue: T, onChange: (property: KProperty<*>, oldValue: T, newValue: T) -> Boolean): ReadWriteProperty<Any?, T> =
        ObservableProperty(initialValue, onChange, null)
}

private class NotNullVar<T : Any>() : ReadWriteProperty<Any?, T> {
    private var value: T? = null

    public override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return value ?: throw IllegalStateException("Property " + property.name + " should be initialized before get.")
    }

    public override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
    }
}

private class ObservableProperty<T>(
    initialValue: T,
    private val veto: ((KProperty<*>, T, T) -> Boolean)?,
    private val after: ((KProperty<*>, T, T) -> Unit)?,
) : ReadWriteProperty<Any?, T> {
    private var value: T = initialValue

    public override fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    public override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val oldValue = this.value
        val v = veto
        if (v != null && !v(property, oldValue, value)) return
        this.value = value
        val a = after
        if (a != null) a(property, oldValue, value)
    }
}
