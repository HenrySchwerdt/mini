/*
 * Mini stdlib: Throwable helpers (stackTraceToString, printStackTrace, suppressed exceptions) and ConcurrentModificationException.
 * The runtime keeps no stack frames: a "stack trace" is the exception with its chain of causes.
 */
package kotlin

public open class ConcurrentModificationException : RuntimeException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public fun Throwable.stackTraceToString(): String {
    val sb = StringBuilder()
    sb.append(this.toString())
    var c: Throwable? = this.cause
    var depth = 0
    while (c != null && depth < 64) {
        sb.append("\nCaused by: ").append(c.toString())
        c = c.cause
        depth++
    }
    sb.append('\n')
    return sb.toString()
}

public fun Throwable.printStackTrace() {
    mini.internal.stderrWrite(stackTraceToString())
}

private object SuppressedExceptions {
    val table: HashMap<Throwable, ArrayList<Throwable>> = HashMap()
}

public fun Throwable.addSuppressed(exception: Throwable) {
    if (exception === this) return
    var l = SuppressedExceptions.table.get(this)
    if (l == null) {
        l = ArrayList<Throwable>()
        SuppressedExceptions.table.put(this, l)
    }
    l.add(exception)
}

public val Throwable.suppressedExceptions: List<Throwable>
    get() = SuppressedExceptions.table.get(this) ?: emptyList()
