package mini.tool

/**
 * A 128-bit non-cryptographic hash (two FNV-1a lanes) for build fingerprints and cache directory names.
 * Pure Kotlin, deterministic across platforms.
 */
class Hasher {
    private var a: Long = -3750763034362895579L // FNV-1a 64 offset basis
    private var b: Long = 0x6c62272e07bb0142L
    private val prime = 1099511628211L

    private fun mix(x: Int) {
        a = (a xor x.toLong()) * prime
        b = (b xor (x.toLong() + 0x9e37)) * prime
        b = b xor (b ushr 29)
    }

    fun add(s: String): Hasher {
        for (c in s) mix(c.code)
        mix(0x1f) // separator so that ("ab","c") != ("a","bc")
        mix(s.length)
        return this
    }

    fun add(n: Long): Hasher = add(n.toString())

    fun hex(): String = hex16(a) + hex16(b)

    private fun hex16(v: Long): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder()
        for (i in 15 downTo 0) sb.append(digits[((v ushr (i * 4)) and 0xf).toInt()])
        return sb.toString()
    }

    companion object {
        fun of(s: String): String = Hasher().add(s).hex()
    }
}
