/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * Ported from kotlin-stdlib Comparator.kt / comparisons.
 */
package kotlin

public fun interface Comparator<T> {
    public fun compare(a: T, b: T): Int

    /** The static functions of java.util.Comparator (`Comparator.naturalOrder<T>()`, `Comparator.comparing { }`, ...). */
    public companion object {
        @Suppress("UNCHECKED_CAST")
        public fun <T : Comparable<T>> naturalOrder(): Comparator<T> = Comparator { a, b -> a.compareTo(b) }

        @Suppress("UNCHECKED_CAST")
        public fun <T : Comparable<T>> reverseOrder(): Comparator<T> = Comparator { a, b -> b.compareTo(a) }

        public fun <T, U : Comparable<U>> comparing(keyExtractor: (T) -> U): Comparator<T> =
            Comparator { a, b -> keyExtractor(a).compareTo(keyExtractor(b)) }

        public fun <T, U> comparing(keyExtractor: (T) -> U, keyComparator: Comparator<in U>): Comparator<T> =
            Comparator { a, b -> keyComparator.compare(keyExtractor(a), keyExtractor(b)) }

        public fun <T> comparingInt(keyExtractor: (T) -> Int): Comparator<T> =
            Comparator { a, b -> keyExtractor(a).compareTo(keyExtractor(b)) }

        public fun <T> comparingLong(keyExtractor: (T) -> Long): Comparator<T> =
            Comparator { a, b -> keyExtractor(a).compareTo(keyExtractor(b)) }

        public fun <T> comparingDouble(keyExtractor: (T) -> Double): Comparator<T> =
            Comparator { a, b -> keyExtractor(a).compareTo(keyExtractor(b)) }

        public fun <T> nullsFirst(c: Comparator<in T>?): Comparator<T?> = Comparator { a, b ->
            if (a == null) (if (b == null) 0 else -1) else if (b == null) 1 else if (c == null) 0 else c.compare(a, b)
        }

        public fun <T> nullsLast(c: Comparator<in T>?): Comparator<T?> = Comparator { a, b ->
            if (a == null) (if (b == null) 0 else 1) else if (b == null) -1 else if (c == null) 0 else c.compare(a, b)
        }
    }
}
