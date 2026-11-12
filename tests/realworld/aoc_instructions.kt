// Advent-of-Code style: parse an assembly-like program with regular expressions and simulate it, plus a boot-code loop detector.

sealed class Instr {
    data class Set(val reg: Char, val value: Operand) : Instr()
    data class Add(val reg: Char, val value: Operand) : Instr()
    data class Mul(val reg: Char, val value: Operand) : Instr()
    data class Mod(val reg: Char, val value: Operand) : Instr()
    data class Jgz(val test: Operand, val offset: Operand) : Instr()
    data class Out(val value: Operand) : Instr()
}

sealed class Operand {
    data class Const(val v: Long) : Operand()
    data class Reg(val r: Char) : Operand()
}

val LINE = Regex("""^(\w{3}) (-?\w+)(?: (-?\w+))?$""")

fun operand(s: String): Operand = s.toLongOrNull()?.let { Operand.Const(it) } ?: Operand.Reg(s[0])

fun parse(source: String): List<Instr> = source.lines().filter { it.isNotBlank() }.map { line ->
    val m = LINE.matchEntire(line.trim()) ?: error("bad line: $line")
    val (op, a, b) = m.destructured
    when (op) {
        "set" -> Instr.Set(a[0], operand(b))
        "add" -> Instr.Add(a[0], operand(b))
        "mul" -> Instr.Mul(a[0], operand(b))
        "mod" -> Instr.Mod(a[0], operand(b))
        "jgz" -> Instr.Jgz(operand(a), operand(b))
        "out" -> Instr.Out(operand(a))
        else -> error("unknown op $op")
    }
}

class Machine(private val program: List<Instr>) {
    val regs = HashMap<Char, Long>()
    val output = ArrayList<Long>()
    var steps = 0

    private fun value(o: Operand): Long = when (o) {
        is Operand.Const -> o.v
        is Operand.Reg -> regs[o.r] ?: 0L
    }

    fun run(limit: Int = 10_000): Boolean {
        var pc = 0
        while (pc in program.indices) {
            if (steps++ >= limit) return false
            when (val i = program[pc]) {
                is Instr.Set -> regs[i.reg] = value(i.value)
                is Instr.Add -> regs[i.reg] = (regs[i.reg] ?: 0L) + value(i.value)
                is Instr.Mul -> regs[i.reg] = (regs[i.reg] ?: 0L) * value(i.value)
                is Instr.Mod -> regs[i.reg] = (regs[i.reg] ?: 0L) % value(i.value)
                is Instr.Out -> output.add(value(i.value))
                is Instr.Jgz -> if (value(i.test) > 0) {
                    pc += value(i.offset).toInt()
                    continue
                }
            }
            pc++
        }
        return true
    }
}

val FACTORIAL = """
set a 1
set b 1
set n 10
mul a b
add b 1
set c b
add c -11
jgz c -4
out a
""".trimIndent()

val FIB = """
set a 0
set b 1
set n 15
out a
set t a
add t b
set a b
set b t
add n -1
jgz n -6
""".trimIndent()

val FOREVER = """
set a 1
add a 1
jgz a -1
""".trimIndent()

// Boot code: acc / jmp / nop, find the loop and repair the corrupted instruction.
data class Boot(val op: String, val arg: Int)

fun runBoot(code: List<Boot>): Pair<Boolean, Int> {
    var pc = 0
    var acc = 0
    val seen = HashSet<Int>()
    while (pc < code.size) {
        if (!seen.add(pc)) return false to acc
        val (op, arg) = code[pc]
        when (op) {
            "acc" -> { acc += arg; pc++ }
            "jmp" -> pc += arg
            else -> pc++
        }
    }
    return true to acc
}

fun main() {
    for ((name, src) in listOf("factorial" to FACTORIAL, "fibonacci" to FIB)) {
        val prog = parse(src)
        val m = Machine(prog)
        val finished = m.run()
        println("$name: ${prog.size} instructions, finished=$finished steps=${m.steps} out=${m.output}")
        println("  registers: " + m.regs.toSortedMap().entries.joinToString { "${it.key}=${it.value}" })
    }
    val loop = Machine(parse(FOREVER))
    println("forever finished=${loop.run(500)} steps=${loop.steps}")
    println(parse("set a -5").first())
    try {
        parse("bogus 1 2")
    } catch (e: IllegalStateException) {
        println("error: ${e.message}")
    }

    val boot = """
        nop +0
        acc +1
        jmp +4
        acc +3
        jmp -3
        acc -99
        acc +1
        jmp -4
        acc +6
    """.trimIndent().lines().map { l -> Boot(l.substringBefore(' '), l.substringAfter(' ').toInt()) }
    val (ok, acc) = runBoot(boot)
    println("boot: terminated=$ok acc=$acc")
    for (i in boot.indices) {
        val b = boot[i]
        val fixed = when (b.op) {
            "jmp" -> b.copy(op = "nop")
            "nop" -> b.copy(op = "jmp")
            else -> continue
        }
        val (done, result) = runBoot(boot.toMutableList().also { it[i] = fixed })
        if (done) println("fixed instruction $i (${b.op} -> ${fixed.op}): acc=$result")
    }
}
