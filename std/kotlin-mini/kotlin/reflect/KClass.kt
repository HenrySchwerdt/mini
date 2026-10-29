/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 */
package kotlin.reflect

/**
 * Marker declarations only: Mini has no reflection. The frontend (fir2ir) requires these
 * classifiers to exist (callable references, `Foo::class` in annotations).
 */
public interface KAnnotatedElement

public interface KClass<T : Any> : KAnnotatedElement

public interface KCallable<out R> : KAnnotatedElement {
    public val name: String
}

public interface KFunction<out R> : KCallable<R>, Function<R>

public interface KProperty<out V> : KCallable<V>

public interface KMutableProperty<V> : KProperty<V>

public interface KProperty0<out V> : KProperty<V>, () -> V

public interface KMutableProperty0<V> : KProperty0<V>, KMutableProperty<V>

public interface KProperty1<T, out V> : KProperty<V>, (T) -> V

public interface KMutableProperty1<T, V> : KProperty1<T, V>, KMutableProperty<V>

public interface KProperty2<D, E, out V> : KProperty<V>, (D, E) -> V

public interface KMutableProperty2<D, E, V> : KProperty2<D, E, V>, KMutableProperty<V>

public interface KType
