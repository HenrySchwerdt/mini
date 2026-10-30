package textutil

/** A library the wasm library depends on (precompiled to a .mlib by `mini`). */
fun shout(s: String): String = s.uppercase() + "!"

fun wordCount(s: String): Int {
    var n = 0
    var inWord = false
    for (c in s) {
        if (c == ' ') inWord = false else if (!inWord) { inWord = true; n++ }
    }
    return n
}
