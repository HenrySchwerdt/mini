#!/usr/bin/env python3
"""A generator of random Strict-mode programs that the memory checker should accept (see docs/fuzzing.md, "Strict variants").

    genstrict.py SEED [--size N]

The programs follow the ownership rules of docs/memory-model.md: every object of a Strict class (`@AllocatedBy(Heap::class)`) is freed
exactly once on every path, borrowed parameters are read only, `@Owned` parameters are consumed, owning fields are moved with
`move()` or freed with their owner. They print values, so the C and LLVM builds can be compared. The runner (`fuzz.py run --strict`)
checks that

  * the checker accepts the program (a rejection is a false positive of the checker or a bug of the generator: `strict-rejected`),
  * the program runs clean under AddressSanitizer, UBSan and LeakSanitizer (a report is a soundness bug of the checker, the
    memory model or the runtime: the checker accepted a program that leaks, frees twice or reads freed memory).

Each scenario is a block of lines that declares its own locals, so blocks can be deleted independently by the reducer and nested in
loops, branches, `try`/`finally` and helper functions.
"""
import random
import sys


class SGen:
    def __init__(self, seed, size=12):
        self.r = random.Random(seed)
        self.size = size
        self.n = 0
        self.funs = []  # (kind, name) of the generated helper functions
        self.decl = []

    def fresh(self, p="v"):
        self.n += 1
        return "%s%d" % (p, self.n)

    def chance(self, p):
        return self.r.random() < p

    def pick(self, xs):
        return self.r.choice(xs)

    def num(self, ivars):
        r = self.r
        if ivars and self.chance(0.6):
            v = self.pick(ivars)
            return v if self.chance(0.6) else "(%s %s %d)" % (v, self.pick(["+", "-", "*"]), r.randint(1, 9))
        return str(r.randint(-5, 99))

    def cond(self, ivars):
        return "(%s %s %s)" % (self.num(ivars), self.pick(["<", ">", "==", "!=", "<=", ">="]), self.num(ivars))

    # ---- declarations
    def declarations(self):
        d = []
        d += [
            "@AllocatedBy(Heap::class)",
            "class Box(val v: Int)",
            "",
            "@AllocatedBy(Heap::class)",
            "class Node(val v: Int, var next: Node?)",
            "",
            "@AllocatedBy(Heap::class)",
            "class Holder(var item: Box?, val tag: Int)",
            "",
            "interface Reader { fun read(): Int }",
            "",
            "fun borrowBox(b: Box, k: Int): Int = b.v * k + %d" % self.r.randint(0, 9),
            "",
            "fun consumeBox(b: @Owned Box): Int {",
            "    val r = b.v + %d" % self.r.randint(0, 9),
            "    b.free()",
            "    return r",
            "}",
            "",
            "fun makeBox(a: Int): @Owned Box = Box(a)",
            "",
            "fun pushNode(list: @Owned Node?, v: Int): @Owned Node = Node(v, list)",
            "",
            "fun lenNodes(n: Node?): Int {",
            "    var c = 0",
            "    @Borrowed var cur = n",
            "    while (cur != null) {",
            "        c++",
            "        cur = cur.next",
            "    }",
            "    return c",
            "}",
            "",
            "fun sumNodes(n: Node?): Int = if (n == null) 0 else n.v + sumNodes(n.next)",
            "",
            "fun swapOut(h: Holder): @Owned Box? {",
            "    val old = h.item.move()",
            "    h.item = null",
            "    return old",
            "}",
            "",
            "fun wrap(b: @Owned Box, t: Int): @Owned Holder = Holder(b, t)",
            "",
        ]
        return d

    # ---- scenarios; each returns a list of lines and uses only its own locals and the int variables in `iv`
    def scenario(self, iv, depth):
        k = self.r.randint(0, 17)
        return getattr(self, "s%d" % k)(iv, depth)

    def s0(self, iv, depth):  # create, use, free
        b = self.fresh("b")
        return ["val %s = Box(%s)" % (b, self.num(iv)), "println(%s.v + %s)" % (b, self.num(iv)), "%s.free()" % b]

    def s1(self, iv, depth):  # both branches free
        b = self.fresh("b")
        return [
            "val %s = Box(%s)" % (b, self.num(iv)),
            "if %s {" % self.cond(iv),
            "    println(%s.v)" % b,
            "    %s.free()" % b,
            "} else {",
            "    %s.free()" % b,
            "}",
        ]

    def s2(self, iv, depth):  # owned parameter
        b = self.fresh("b")
        return ["val %s = makeBox(%s)" % (b, self.num(iv)), "println(borrowBox(%s, %s))" % (b, self.num(iv)), "println(consumeBox(%s))" % b]

    def s3(self, iv, depth):  # list
        l, i = self.fresh("l"), self.fresh("i")
        return [
            "var %s: Node? = null" % l,
            "for (%s in 1..%d) %s = pushNode(%s, %s * %s)" % (i, self.r.randint(0, 6), l, l, i, self.num(iv)),
            "println(lenNodes(%s))" % l,
            "println(sumNodes(%s))" % l,
            "%s.free()" % l,
        ]

    def s4(self, iv, depth):  # holder, partial move
        h, o = self.fresh("h"), self.fresh("o")
        out = ["val %s = wrap(Box(%s), %s)" % (h, self.num(iv), self.num(iv)), "println(%s.item?.v)" % h, "println(%s.tag)" % h]
        if self.chance(0.6):
            out += ["val %s = swapOut(%s)" % (o, h), "println(%s?.v)" % o, "%s.free()" % o]
        out.append("%s.free()" % h)
        return out

    def s5(self, iv, depth):  # try / finally
        b = self.fresh("b")
        return [
            "val %s = Box(%s)" % (b, self.num(iv)),
            "try {",
            "    println(%s.v)" % b,
            "    if %s throw IllegalStateException(\"x\")" % self.cond(iv),
            "    println(%s.v + 1)" % b,
            "} catch (e: IllegalStateException) {",
            "    println(\"caught \" + %s.v)" % b,
            "} finally {",
            "    %s.free()" % b,
            "}",
        ]

    def s6(self, iv, depth):  # loop: allocate and free per iteration, free before continue
        i, b = self.fresh("i"), self.fresh("b")
        return [
            "for (%s in 0 until %d) {" % (i, self.r.randint(1, 6)),
            "    val %s = Box(%s * %s)" % (b, i, self.num(iv)),
            "    if (%s %% 2 == %d) {" % (i, self.r.randint(0, 1)),
            "        %s.free()" % b,
            "        continue",
            "    }",
            "    println(%s.v)" % b,
            "    %s.free()" % b,
            "}",
        ]

    def s7(self, iv, depth):  # object expression capturing a borrowed owner
        b = self.fresh("b")
        return ["val %s = Box(%s)" % (b, self.num(iv)), "val r%s = object : Reader { override fun read(): Int = %s.v + %s }" % (b, b, self.num(iv)), "println(r%s.read())" % b, "%s.free()" % b]

    def s8(self, iv, depth):  # local lambda reading an owner
        b, f = self.fresh("b"), self.fresh("f")
        return ["val %s = Box(%s)" % (b, self.num(iv)), "val %s = { %s.v + %s }" % (f, b, self.num(iv)), "println(%s())" % f, "%s.free()" % b]

    def s9(self, iv, depth):  # nullable owner
        c = self.fresh("c")
        return ["var %s: Box? = Box(%s)" % (c, self.num(iv)), "println(%s?.v)" % c, "%s.free()" % c, "%s = null" % c, "println(%s == null)" % c]

    def s10(self, iv, depth):  # consume in both branches
        b = self.fresh("b")
        return ["val %s = Box(%s)" % (b, self.num(iv)), "if %s println(consumeBox(%s)) else %s.free()" % (self.cond(iv), b, b)]

    def s11(self, iv, depth):  # two owners, swapped by moves
        a, b, t = self.fresh("a"), self.fresh("b"), self.fresh("t")
        return [
            "var %s = Box(%s)" % (a, self.num(iv)),
            "var %s = Box(%s)" % (b, self.num(iv)),
            "val %s = %s.move()" % (t, a),
            "%s = %s.move()" % (a, b),
            "%s = %s.move()" % (b, t),
            "println(%s.v - %s.v)" % (a, b),
            "%s.free()" % a,
            "%s.free()" % b,
        ]

    def s12(self, iv, depth):  # while loop with a counter and a list built from owners
        l, c = self.fresh("l"), self.fresh("c")
        return [
            "var %s: Node? = null" % l,
            "var %s = 0" % c,
            "while (%s < %d) {" % (c, self.r.randint(1, 5)),
            "    %s = pushNode(%s, %s + %s)" % (l, l, c, self.num(iv)),
            "    %s++" % c,
            "}",
            "println(sumNodes(%s))" % l,
            "%s.free()" % l,
        ]

    def s13(self, iv, depth):  # when on an int, owner freed in every arm
        b = self.fresh("b")
        return [
            "val %s = Box(%s)" % (b, self.num(iv)),
            "when (%s.v %% 3) {" % b,
            "    0 -> { println(\"zero\"); %s.free() }" % b,
            "    1 -> println(consumeBox(%s))" % b,
            "    else -> %s.free()" % b,
            "}",
        ]

    def s14(self, iv, depth):  # nested scope, early return from a helper-like block via run
        b = self.fresh("b")
        return ["val %s = Box(%s)" % (b, self.num(iv)), "val r%s = run { %s.v * 2 }" % (b, b), "println(r%s)" % b, "%s.free()" % b]

    def s15(self, iv, depth):  # plain data next to owners: strings, lists, StringBuilder
        b = self.fresh("b")
        return [
            "val %s = Box(%s)" % (b, self.num(iv)),
            "val s%s = StringBuilder()" % b,
            "for (j in 0 until %d) s%s.append(%s.v + j).append(',')" % (self.r.randint(0, 4), b, b),
            "println(s%s.toString())" % b,
            "println(listOf(%s.v, %s).map { it * 2 })" % (b, self.num(iv)),
            "%s.free()" % b,
        ]

    def s16(self, iv, depth):  # holder in a loop, freed with its item
        h, i = self.fresh("h"), self.fresh("i")
        return [
            "for (%s in 0 until %d) {" % (i, self.r.randint(1, 4)),
            "    val %s = Holder(Box(%s + %s), %s)" % (h, i, self.num(iv), i),
            "    println(%s.item!!.v + %s.tag)" % (h, h),
            "    %s.free()" % h,
            "}",
        ]

    def s17(self, iv, depth):  # try/finally in a loop with break
        i, b = self.fresh("i"), self.fresh("b")
        return [
            "for (%s in 0 until %d) {" % (i, self.r.randint(1, 5)),
            "    val %s = Box(%s)" % (b, i),
            "    try {",
            "        if (%s == %d) break" % (i, self.r.randint(0, 4)),
            "        println(%s.v)" % b,
            "    } finally {",
            "        %s.free()" % b,
            "    }",
            "}",
        ]

    # ---- wrappers: a block inside a loop, an if, a helper function
    def block(self, iv, depth):
        r = self.r
        lines = []
        for _ in range(r.randint(1, 2)):
            lines += self.scenario(iv, depth)
        return lines

    def wrapped(self, iv, depth):
        r = self.r
        k = r.randint(0, 5) if depth > 0 else 0
        if k <= 2:
            return self.block(iv, depth)
        if k == 3:
            i = self.fresh("i")
            inner = self.block(iv + [i], depth - 1)
            return ["for (%s in 0 until %d) {" % (i, r.randint(1, 3))] + ["    " + l for l in inner] + ["}"]
        if k == 4:
            inner = self.block(iv, depth - 1)
            other = self.block(iv, depth - 1)
            return ["if %s {" % self.cond(iv)] + ["    " + l for l in inner] + ["} else {"] + ["    " + l for l in other] + ["}"]
        name = self.fresh("helper")
        p = self.fresh("p")
        inner = self.block([p], depth - 1)
        self.decl += ["fun %s(%s: Int) {" % (name, p)] + ["    " + l for l in inner] + ["}", ""]
        return ["%s(%s)" % (name, self.num(iv))]

    def program(self):
        body = []
        for _ in range(self.size):
            body.append(self.wrapped([], 2))
        return body

    def render(self, body):
        out = ["@file:MemoryMode(Mode.Strict)", "", "import mini.memory.*", "", "// --- generated ---"]
        out += self.declarations()
        out += self.decl
        out.append("fun main() {")
        for b in body:
            out += ["    " + l for l in b]
        out.append("}")
        return "\n".join(out) + "\n"


def generate(seed, size=12):
    g = SGen(seed, size)
    body = g.program()
    return g.render(body)


if __name__ == "__main__":
    args = sys.argv[1:]
    size = 12
    if "--size" in args:
        i = args.index("--size")
        size = int(args[i + 1])
        del args[i : i + 2]
    sys.stdout.write(generate(int(args[0]), size))
