/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 */
package kotlin.reflect

/**
 * Marker declarations only: Mini has no reflection. The frontend (fir2ir) requires these
 * classifiers to exist (callable references, `Foo::class` in annotations).
 */
public interface KAnnotatedElement

public interface KClass<T : Any> : KClassifier, KAnnotatedElement {
    /** The simple name of the class (without package and enclosing classes), `null` for anonymous classes. */
    public val simpleName: String?

    /** The fully qualified name of the class, `null` for anonymous and local classes. */
    public val qualifiedName: String?

    /** Is [value] an instance of this class (the class itself, a subclass, or a class implementing this interface)? `null` is not. */
    public fun isInstance(value: Any?): Boolean
}

/**
 * What `Foo::class` and `x::class` evaluate to: the Mini backend creates it from the type info name
 * (`MkTypeInfo.name`, the Kotlin qualified name). Only the names are available (no members, no reflection).
 */
internal class KClassImpl<T : Any>(private val name: String) : KClass<T> {
    override val simpleName: String?
        get() = if (name.indexOf('$') >= 0) null else name.substringAfterLast('.')

    override val qualifiedName: String?
        get() = if (name.indexOf('$') >= 0) null else name

    override fun isInstance(value: Any?): Boolean = mini.internal.typeIsNamed(value, name)

    override fun equals(other: Any?): Boolean = other is KClassImpl<*> && other.name == name

    override fun hashCode(): Int = name.hashCode()

    override fun toString(): String = "class $name"
}

public interface KCallable<out R> : KAnnotatedElement {
    public val name: String
}

public interface KFunction<out R> : KCallable<R>, Function<R>

public interface KProperty<out V> : KCallable<V>

public interface KMutableProperty<V> : KProperty<V>

public interface KProperty0<out V> : KProperty<V>, () -> V {
    public fun get(): V
}

public interface KMutableProperty0<V> : KProperty0<V>, KMutableProperty<V> {
    public fun set(value: V)
}

public interface KProperty1<T, out V> : KProperty<V>, (T) -> V {
    public fun get(receiver: T): V
}

public interface KMutableProperty1<T, V> : KProperty1<T, V>, KMutableProperty<V> {
    public fun set(receiver: T, value: V)
}

/*
 * What `::prop`, `obj::prop` and `Class::prop` evaluate to. The compiler creates them with closures for the getter (and setter)
 * of the property (Ir2MirClosures.propertyReference).
 */
internal class KProperty0Impl<V>(override val name: String, private val getter: () -> V) : KProperty0<V> {
    override fun get(): V = getter()
    override fun invoke(): V = getter()
    override fun equals(other: Any?): Boolean = other is KProperty0Impl<*> && name == other.name && getter == other.getter
    override fun hashCode(): Int = name.hashCode() * 31 + getter.hashCode()
    override fun toString(): String = "property $name"
}

internal class KMutableProperty0Impl<V>(override val name: String, private val getter: () -> V, private val setter: (V) -> Unit) : KMutableProperty0<V> {
    override fun get(): V = getter()
    override fun set(value: V) { setter(value) }
    override fun invoke(): V = getter()
    override fun equals(other: Any?): Boolean = other is KMutableProperty0Impl<*> && name == other.name && getter == other.getter && setter == other.setter
    override fun hashCode(): Int = name.hashCode() * 31 + getter.hashCode() * 31 + setter.hashCode()
    override fun toString(): String = "property $name"
}

internal class KProperty1Impl<T, V>(override val name: String, private val getter: (T) -> V) : KProperty1<T, V> {
    override fun get(receiver: T): V = getter(receiver)
    override fun invoke(p1: T): V = getter(p1)
    override fun equals(other: Any?): Boolean = other is KProperty1Impl<*, *> && name == other.name && getter == other.getter
    override fun hashCode(): Int = name.hashCode() * 31 + getter.hashCode()
    override fun toString(): String = "property $name"
}

internal class KMutableProperty1Impl<T, V>(override val name: String, private val getter: (T) -> V, private val setter: (T, V) -> Unit) : KMutableProperty1<T, V> {
    override fun get(receiver: T): V = getter(receiver)
    override fun set(receiver: T, value: V) { setter(receiver, value) }
    override fun invoke(p1: T): V = getter(p1)
    override fun equals(other: Any?): Boolean = other is KMutableProperty1Impl<*, *> && name == other.name && getter == other.getter && setter == other.setter
    override fun hashCode(): Int = name.hashCode() * 31 + getter.hashCode() * 31 + setter.hashCode()
    override fun toString(): String = "property $name"
}

public interface KProperty2<D, E, out V> : KProperty<V>, (D, E) -> V {
    public fun get(receiver1: D, receiver2: E): V
}

public interface KMutableProperty2<D, E, V> : KProperty2<D, E, V>, KMutableProperty<V> {
    public fun set(receiver1: D, receiver2: E, value: V)
}

