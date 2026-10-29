package mkt.test

import mkt.*
import mkt.collections.*
import mkt.text.*

/* Only compiled in the JVM run: proves the tests exercise the Mini stdlib sources, not kotlin-stdlib. */
fun testUsesMiniStdlib() {
    eq("mkt.collections.ArrayList", listOf(1, 2).javaClass.name)
    eq("mkt.collections.LinkedHashMap", mapOf(1 to 2).javaClass.name)
    eq("mkt.collections.HashSet", hashSetOf(1).javaClass.name)
    eq("mkt.text.StringBuilder", StringBuilder().javaClass.name)
    eq("mkt.Pair", (1 to 2).javaClass.name)
}
