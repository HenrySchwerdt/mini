/*
 * Placeholder for `java.lang.Thread` (default-imported on the JVM, so portable sources such as
 * mini.memory.Spawn mention it unqualified). Real threads are not part of stdlib v1-S.
 */
package java.lang

public class Thread(private val block: () -> Unit) {
    public fun start() {
        error("threads are not supported yet")
    }

    public fun join() {
        error("threads are not supported yet")
    }
}
