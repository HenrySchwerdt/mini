package kotlin.enums

/** The type of `Color.entries`: an immutable list of the entries of an enum class, in declaration order. */
public interface EnumEntries<E : Enum<E>> : List<E>

/** What `Color.entries` evaluates to: a read-only view of the `values()` array. The compiler creates it. */
internal class EnumEntriesImpl<E : Enum<E>>(private val items: Array<E>) : AbstractList<E>(), EnumEntries<E> {
    override val size: Int
        get() = items.size

    override fun get(index: Int): E {
        if (index < 0 || index >= items.size) mini.internal.throwIndexOutOfBounds("index: $index, size: ${items.size}")
        return items[index]
    }
}
