package mini.memory

/** Runs [block] on a new thread. A thread boundary: see M0206 and docs/memory-model.md, section 5.9. */
fun spawn(@ThreadBoundary block: () -> Unit): Joinable {
    val t = Thread(block)
    t.start()
    return Joinable(t)
}

class Joinable internal constructor(private val thread: Thread) {
    fun join() = thread.join()
}
