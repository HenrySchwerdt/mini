import tmpl.*

data class Item(val name: String, val price: Double)

fun main() {
    val mail = """
        Dear {{customer|title}},
        {{#if vip}}As a valued customer you get free shipping.{{else}}Shipping costs 5.00.{{/if}}
        Your order:
        {{#each lines}}  {{index}}. {{item}}{{#if last}} (last){{/if}}
        {{/each}}Total items: {{count}}. Reference {{ref|upper}} ({{ref|len}} chars).
    """.trimIndent()
    val t = Template.parse(mail)
    println(t.render(mapOf("customer" to "ada LOVELACE", "vip" to true, "lines" to listOf("pen", "ink", "paper"), "count" to 3, "ref" to "ab-12")))
    println("----")
    println(t.render(mapOf("customer" to "bob", "vip" to false, "lines" to emptyList<String>(), "count" to 0, "ref" to "x")))
    println("----")
    println(template("{{#each rows}}[{{item}}]{{/each}}") { put("rows", (1..4).map { it * it }) })
    println(template("{{a}}-{{b|upper}}") { put("a", 1); put("b", "two") })
    println(Template.parse("{{#each xs}}{{#each item}}{{item}},{{/each}};{{/each}}").render(mapOf("xs" to listOf(listOf(1, 2), listOf(3)))))
    val custom = mapOf("shout" to { s: String -> s.uppercase() + "!" }, "rev" to { s: String -> s.reversed() })
    println(Template.parse("{{w|shout}} {{w|rev|shout}}").render(mapOf("w" to "hey"), custom))
    for (bad in listOf("{{x", "{{#each a}}x", "{{/each}}", "{{#if a}}x", "{{unknown}}", "{{n|nofilter}}", "{{#each n}}{{/each}}")) {
        try {
            println(Template.parse(bad).render(mapOf("n" to 1)))
        } catch (e: TemplateError) {
            println("error: ${e.message}")
        }
    }
    val items = listOf(Item("lamp", 19.5), Item("desk", 120.0))
    println(template("{{#each items}}{{item}}\n{{/each}}") { put("items", items) })
}
