// Stress: string building, concatenation, splitting and joining, round after round.
fun main(args: Array<String>) {
    val scale = if (args.size > 0) args[0].toInt() else 1
    var check = 0L
    for (round in 0 until 8 * scale) {
        val sb = StringBuilder()
        for (i in 0 until 50000) {
            sb.append("item").append(i).append(',')
        }
        val s = sb.toString()
        val parts = s.split(',')
        check += parts.size
        var joined = ""
        for (i in 0 until 2000) joined += parts[i]
        check += joined.length
        val lines = ArrayList<String>()
        for (i in 0 until 20000) lines.add("line " + i + " of " + round)
        check += lines.joinToString("\n").length
        val upper = parts.map { it.uppercase() }
        check += upper.size
    }
    println(check)
}
