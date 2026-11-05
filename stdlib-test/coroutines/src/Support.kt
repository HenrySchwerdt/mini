package mini.coroutinestests

/** A log that the coroutines of a test append to, to check the order of events. */
class EventLog {
    private val items = ArrayList<String>()

    fun add(s: String) {
        items.add(s)
    }

    override fun toString(): String = items.joinToString(",")
}
