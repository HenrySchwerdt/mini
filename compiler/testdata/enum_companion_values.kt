// values() and valueOf() written inside the companion object of an enum class.
enum class Game {
    ROCK, PAPER, SCISSORS;

    companion object {
        val all = values()
        val scissors = valueOf("SCISSORS")

        fun parse(s: String): Game? {
            for (g in values()) if (g.name == s) return g
            return null
        }
    }
}

fun main() {
    println(Game.all.size)
    println(Game.scissors)
    println(Game.parse("PAPER"))
    println(Game.parse("LIZARD"))
}
