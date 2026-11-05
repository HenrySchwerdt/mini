package kotlin.coroutines.intrinsics

/**
 * What a suspend function returns instead of its value when it has suspended: COROUTINE_SUSPENDED, or an object of a
 * subclass that carries a request to the code that resumes the coroutine (the sequence builders hand the yielded value
 * up this way, so that no object points back from the coroutine to its driver: there are no reference cycles).
 */
public abstract class SuspendMarker

private object CoroutineSuspendedMarker : SuspendMarker() {
    override fun toString(): String = "COROUTINE_SUSPENDED"
}

/** The value a suspend function returns when it has suspended. */
public val COROUTINE_SUSPENDED: Any = CoroutineSuspendedMarker
