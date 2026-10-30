/**
 * The linear congruential generator of `java.util.Random` (same seed scrambling, `next`, `nextInt(bound)`), so the
 * fuzz tests mutate exactly the inputs they always did, without any JVM class.
 */
class JavaRandom(seed: Long) {
    private var state: Long = (seed xor 0x5DEECE66DL) and ((1L shl 48) - 1)

    private fun next(bits: Int): Int {
        state = (state * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        return (state ushr (48 - bits)).toInt()
    }

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next(31)
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
        var u = r
        while (true) {
            r = u % bound
            if (u - r + m >= 0) break
            u = next(31)
        }
        return r
    }
}
