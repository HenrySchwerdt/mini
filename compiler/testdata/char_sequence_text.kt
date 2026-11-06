fun main() {
    val cs: CharSequence = "  Hello, World  "
    println(cs.isEmpty()); println(cs.isNotEmpty()); println(cs.isBlank()); println(cs.isNotBlank())
    println(cs.trim()); println(cs.trimStart()); println(cs.trimEnd())
    println(cs.startsWith("  H")); println(cs.endsWith("  ")); println(cs.contains("World")); println(cs.contains('W'))
    println(cs.indexOf("o")); println(cs.indexOf('o', 6)); println(cs.lastIndexOf('o'))
    println(cs.lines()); println(cs.split(",")); println(cs.take(4)); println(cs.drop(10)); println(cs.takeLast(3)); println(cs.dropLast(3))
    println(cs.first()); println(cs.last()); println(cs.count { it == 'l' }); println(cs.any { it == 'W' }); println(cs.all { it != 'z' })
    println(cs.filter { it != ' ' }); println(cs.reversed()); println(cs.padStart(20, '*')); println(cs.padEnd(20, '-'))
    println(cs.substring(2, 7)); println(cs.removePrefix("  ")); println(cs.removeSuffix("  "))
    println(cs.toList()); println(cs.map { it.uppercaseChar() }); println(cs.firstOrNull()); println(cs.lastOrNull())
    println(cs.none()); println(cs.commonPrefixWith("  Help")); println(cs.regionMatches(2, "Hello", 0, 5))
    println(cs.toString().length); println(cs.trim { it == ' ' })
    val sb = StringBuilder("  abc ")
    val c2: CharSequence = sb
    println(c2.isBlank()); println(c2.trim()); println(c2.indexOf("b")); println(c2.split(" ")); println(c2.startsWith("  a"))
    println(c2.lines()); println(c2.contains("bc")); println(c2.count()); println(c2.sumOf { it.code })
    val n: CharSequence? = null
    println(n.isNullOrEmpty()); println(n.isNullOrBlank()); println(c2.isNullOrEmpty())
    println(cs.chunked(5)); println(cs.windowed(5, 5)); println(cs.zip("abc"))
    println(cs.associate { it to 1 }.size); println(cs.groupBy { it }.size); println(cs.indexOfFirst { it == 'l' }); println(cs.indexOfLast { it == 'l' })
    println(cs.endsWith('d')); println(cs.startsWith(' ')); println(cs.elementAt(2)); println(cs.getOrNull(100)); println(cs.single { it == 'H' })
    println(cs.partition { it == 'l' }); println(cs.asSequence().count()); println(cs.drop(2).take(3))
    println(cs.dropWhile { it == ' ' }); println(cs.takeWhile { it == ' ' }); println(cs.trimStart { it == ' ' }); println(cs.trimEnd { it == ' ' })
}
