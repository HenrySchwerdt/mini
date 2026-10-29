package mini.frontend.driver

import mini.frontend.parseKotlin
import mini.frontend.sema.SourceInput
import mini.io.readTextFile

/*
 *   frontend-driver [--repeat N] <file.kt>...
 *
 * Without --repeat it prints `describeFile` for every file. With --repeat N it parses every file N times and prints only
 * the totals (used to measure the parse speed). Pure Kotlin on top of the frontend and mini.io; compiled by minic itself.
 */
fun main(args: Array<String>) {
    if (args.size > 0 && args[0] == "--sema") {
        // --sema <input files...> --report <files...>: semantic dump of the reported files
        val inputs = ArrayList<SourceInput>()
        val report = HashSet<String>()
        var reporting = false
        var j = 1
        while (j < args.size) {
            if (args[j] == "--report") {
                reporting = true
            } else if (reporting) {
                report.add(args[j])
            } else {
                inputs.add(SourceInput(args[j], readTextFile(args[j])))
            }
            j++
        }
        for (r in report) {
            var known = false
            for (i in inputs) if (i.path == r) known = true
            if (!known) inputs.add(SourceInput(r, readTextFile(r)))
        }
        print(describeSema(inputs, report))
        return
    }
    var repeat = 0
    val files = ArrayList<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] == "--repeat" && i + 1 < args.size) {
            repeat = args[i + 1].toInt()
            i += 2
        } else {
            files.add(args[i])
            i += 1
        }
    }
    val texts = ArrayList<String>()
    for (f in files) texts.add(readTextFile(f))
    if (repeat <= 0) {
        var k = 0
        while (k < files.size) {
            print(describeFile(files[k], texts[k]))
            k++
        }
        return
    }
    var lines = 0L
    var tokens = 0L
    var errors = 0L
    var round = 0
    while (round < repeat) {
        var k = 0
        while (k < texts.size) {
            val r = parseKotlin(texts[k])
            lines += countLines(texts[k])
            tokens += r.tokens.size
            errors += r.errors.size
            k++
        }
        round++
    }
    println("parsed " + files.size + " files x " + repeat + ": " + lines + " lines, " + tokens + " tokens, " + errors + " errors")
}
