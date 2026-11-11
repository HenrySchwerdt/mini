// CSV parser and writer (quoted fields, embedded commas, newlines and quotes), typed rows, aggregation and a text report.

import kotlin.math.roundToLong

class CsvError(message: String) : Exception(message)

object Csv {
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var sawAny = false
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else inQuotes = false
                } else field.append(c)
            } else when (c) {
                '"' -> {
                    if (field.isNotEmpty()) throw CsvError("quote inside an unquoted field")
                    inQuotes = true
                    sawAny = true
                }
                delimiter -> {
                    row.add(field.toString())
                    field.clear()
                    sawAny = true
                }
                '\r' -> {}
                '\n' -> {
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = ArrayList()
                    sawAny = false
                }
                else -> {
                    field.append(c)
                    sawAny = true
                }
            }
            i++
        }
        if (inQuotes) throw CsvError("unterminated quoted field")
        if (sawAny || field.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun escape(s: String, d: Char): String =
        if (s.any { it == d || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun write(rows: List<List<String>>, delimiter: Char = ','): String =
        rows.joinToString("\n", postfix = "\n") { r -> r.joinToString(delimiter.toString()) { escape(it, delimiter) } }
}

data class Sale(val region: String, val product: String, val units: Int, val price: Double, val note: String) {
    val revenue: Double get() = units * price
}

fun toSales(rows: List<List<String>>): List<Sale> {
    val header = rows.first()
    val idx = header.withIndex().associate { it.value to it.index }
    return rows.drop(1).mapIndexed { n, r ->
        fun col(name: String) = r.getOrNull(idx[name] ?: throw CsvError("missing column $name")) ?: ""
        Sale(col("region"), col("product"), col("units").toIntOrNull() ?: throw CsvError("row ${n + 1}: bad units '${col("units")}'"),
            col("price").toDouble(), col("note"))
    }
}

fun money(x: Double): String {
    val cents = (x * 100).roundToLong()
    return (cents / 100).toString() + "." + (cents % 100).toString().padStart(2, '0')
}

val DATA = listOf(
    "region,product,units,price,note",
    "north,widget,10,2.50,\"first, big order\"",
    "south,widget,4,2.75,",
    "north,gadget,3,10.00,\"says \"\"rush\"\"\"",
    "east,gadget,7,9.50,\"two",
    "lines\"",
    "south,gizmo,12,1.25,",
    "east,widget,1,3.00,late",
).joinToString("\n", postfix = "\n")

fun main() {
    val rows = Csv.parse(DATA)
    println("parsed ${rows.size} rows, ${rows[0].size} columns")
    for (r in rows) println(r.joinToString(" | ") { it.replace("\n", "\\n") })
    val sales = toSales(rows)
    println()
    val byRegion = sales.groupBy { it.region }
    println("region    units   revenue")
    for ((region, list) in byRegion.toSortedMap()) {
        println(region.padEnd(8) + list.sumOf { it.units }.toString().padStart(6) + money(list.sumOf { it.revenue }).padStart(10))
    }
    val top = sales.maxByOrNull { it.revenue }!!
    println("best sale: ${top.product} in ${top.region} = ${money(top.revenue)}")
    val perProduct = sales.groupingBy { it.product }.fold(0.0) { acc, s -> acc + s.revenue }
    println(perProduct.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${money(it.value)}" })
    println("average price: ${money(sales.map { it.price }.average())}")
    println()
    val out = Csv.write(listOf(listOf("name", "comment"), listOf("a,b", "say \"hi\""), listOf("plain", "multi\nline")))
    print(out)
    println(Csv.parse(out) == listOf(listOf("name", "comment"), listOf("a,b", "say \"hi\""), listOf("plain", "multi\nline")))
    println(Csv.parse("a;b;c\n1;;3", ';'))
    for (bad in listOf("a,\"b", "ab\"c,d")) {
        try {
            Csv.parse(bad)
        } catch (e: CsvError) {
            println("error: ${e.message}")
        }
    }
    try {
        toSales(Csv.parse("region,product,units,price,note\nx,y,many,1.0,"))
    } catch (e: CsvError) {
        println("error: ${e.message}")
    }
}
