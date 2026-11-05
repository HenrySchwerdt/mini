fun main() {
    val sb = StringBuilder()
    sb.append(1.5f).append("xyz", 0, 1).insert(0, 5).replace(0, 1, "zz")
    println(sb)
    println(sb.indexOf("x")); println(sb.lastIndexOf("x")); println(sb.capacity())
    sb.deleteCharAt(0); println(sb.length); println(sb.contentEquals("z1.5x"))
    sb.setCharAt(0, 'q'); println(sb); sb.appendLine(2L).appendLine(true); print(sb)
    println(sb.first()); println(sb.lastIndex); sb.insert(0, 3.5).insert(0, false); println(sb.substring(0..2))
    println(buildString { append("a"); append('b'); append(1); appendLine(); append(2.0) })
}
