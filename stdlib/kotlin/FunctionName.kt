package kotlin

/**
 * `KCallable.name` of a function reference held in a value (`val r = C::f; r.name`): the compiler lowers `r.name` for a value of
 * function type to a call of this. The closure class of a reference is named `<enclosing function>.::<name>$<n>`
 * (Ir2MirClosures.functionReference). A lambda has no such name.
 */
internal fun functionObjectName(f: Any): String {
    val full = f::class.toString().removePrefix("class ")
    val at = full.lastIndexOf("::")
    if (at < 0) return full
    var end = full.length
    var j = end
    while (j > at + 2 && full[j - 1] in '0'..'9') j--
    if (j > at + 2 && full[j - 1] == '$') end = j - 1
    return full.substring(at + 2, end)
}
