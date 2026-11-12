package either

/** A value that is either a failure ([Left]) or a success ([Right]); right-biased. */
sealed class Either<out L, out R> {
    data class Left<out L>(val value: L) : Either<L, Nothing>()
    data class Right<out R>(val value: R) : Either<Nothing, R>()

    val isRight: Boolean get() = this is Right
    val isLeft: Boolean get() = this is Left

    inline fun <T> fold(onLeft: (L) -> T, onRight: (R) -> T): T = when (this) {
        is Left -> onLeft(value)
        is Right -> onRight(value)
    }

    inline fun <T> map(f: (R) -> T): Either<L, T> = when (this) {
        is Left -> this
        is Right -> Right(f(value))
    }

    inline fun <T> flatMap(f: (R) -> Either<@UnsafeVariance L, T>): Either<L, T> = when (this) {
        is Left -> this
        is Right -> f(value)
    }

    inline fun <T> mapLeft(f: (L) -> T): Either<T, R> = when (this) {
        is Left -> Left(f(value))
        is Right -> this
    }

    fun getOrNull(): R? = (this as? Right)?.value
    fun leftOrNull(): L? = (this as? Left)?.value
    inline fun getOrElse(default: (L) -> @UnsafeVariance R): R = when (this) {
        is Left -> default(value)
        is Right -> value
    }

    inline fun onRight(f: (R) -> Unit): Either<L, R> {
        if (this is Right) f(value)
        return this
    }

    inline fun onLeft(f: (L) -> Unit): Either<L, R> {
        if (this is Left) f(value)
        return this
    }

    companion object {
        inline fun <R> catching(block: () -> R): Either<Throwable, R> = try {
            Right(block())
        } catch (e: Throwable) {
            Left(e)
        }
    }
}

fun <L> L.left(): Either<L, Nothing> = Either.Left(this)
fun <R> R.right(): Either<Nothing, R> = Either.Right(this)

inline fun <L, R> R?.toEither(ifNull: () -> L): Either<L, R> = if (this == null) Either.Left(ifNull()) else Either.Right(this)

/** Collects all successes, or the first failure. */
fun <L, R> List<Either<L, R>>.sequence(): Either<L, List<R>> {
    val out = ArrayList<R>()
    for (e in this) when (e) {
        is Either.Left -> return e
        is Either.Right -> out.add(e.value)
    }
    return Either.Right(out)
}

inline fun <T, L, R> Iterable<T>.traverse(f: (T) -> Either<L, R>): Either<L, List<R>> {
    val out = ArrayList<R>()
    for (x in this) when (val r = f(x)) {
        is Either.Left -> return r
        is Either.Right -> out.add(r.value)
    }
    return Either.Right(out)
}

/** Accumulates every failure instead of stopping at the first. */
fun <L, R> Iterable<Either<L, R>>.partitionEithers(): Pair<List<L>, List<R>> {
    val ls = ArrayList<L>()
    val rs = ArrayList<R>()
    for (e in this) when (e) {
        is Either.Left -> ls.add(e.value)
        is Either.Right -> rs.add(e.value)
    }
    return ls to rs
}

fun <L, A, B, R> zip(a: Either<L, A>, b: Either<L, B>, f: (A, B) -> R): Either<L, R> =
    a.flatMap { x -> b.map { y -> f(x, y) } }
