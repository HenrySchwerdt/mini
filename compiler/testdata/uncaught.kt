// An exception that escapes main: finally blocks run, stdout is flushed, the report goes to
// stderr and the exit status is 1.

class ConfigError(val key: String) : IllegalStateException("bad config: " + key)

fun load(key: String): Int {
    try {
        println("loading " + key)
        if (key == "port") throw ConfigError(key)
        return key.length
    } finally {
        println("cleanup " + key)
    }
}

fun main() {
    println(load("host"))
    println(load("port"))
    println("not reached")
}
