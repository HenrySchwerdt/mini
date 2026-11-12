package vault

class Vault(private val secret: String) {
    internal var opened = 0
    private fun mask(s: String) = "*".repeat(s.length)
    fun hint(): String = mask(secret)
    fun open(pin: Int): String? {
        if (pin != 1234) return null
        opened++
        return secret
    }
}

internal fun backdoor(v: Vault): Int = v.opened

internal class Hidden(val x: Int)

fun publicApi(): Int = backdoor(Vault("x")) + Hidden(1).x
