#!/usr/bin/env python3
"""A generator of random, deterministic, terminating Kotlin programs for differential testing (see docs/fuzzing.md).

    gen.py SEED            print the program of this seed
    gen.py SEED --size N   a bigger or smaller program (number of top-level statements, default 14)

A program is well typed by construction (every expression is built for a wanted type), has no unbounded loop or recursion and prints
values and checksums only: integers, strings, booleans, collections, data classes. Every exception is caught in `main` (the class
name is printed), so the exit status is always 0 and the output is the whole observable behaviour.

The program is a list of *units*: the declarations, and the statements of `run()`, each one a list of lines. tools/fuzz/reduce.py
deletes units to minimise a failing program; every unit prints on its own, so deleting one keeps the others valid as long as the
variables it declares are not used later (the reducer simply tries and keeps what still reproduces).
"""
import random
import sys

INT_LIT = [0, 1, 2, 3, 5, 7, 10, 31, 100, 255, 1000, 65535, 2147483647, -1, -2, -7, -128, -2147483647]
LONG_LIT = [0, 1, 2, 3, 10, 1000, 4294967296, 9007199254740993, 9223372036854775807, -1, -4294967296]
WORDS = ["a", "b", "xy", "foo", "Bar", "hello", "", " ", "q1", "Zeta", "mini", "ab ba", "kotlin", "été", "日本"]
CHARS = ["'a'", "'z'", "'A'", "'0'", "' '", "'x'", "'é'"]


class Env:
    """The variables in scope, by type."""

    def __init__(self, parent=None):
        self.vars = {}  # name -> (type, mutable)
        self.parent = parent

    def add(self, name, ty, mutable=False):
        self.vars[name] = (ty, mutable)

    def all(self):
        d = dict(self.parent.all()) if self.parent else {}
        d.update(self.vars)
        return d

    def of(self, ty):
        return [n for n, (t, _) in sorted(self.all().items()) if t == ty]

    def mutable(self, ty):
        return [n for n, (t, m) in sorted(self.all().items()) if t == ty and m]


class Gen:
    def __init__(self, seed, size=14):
        self.r = random.Random(seed)
        self.seed = seed
        self.size = size
        self.n = 0  # name counter
        self.decls = []  # units of declarations
        self.funs = []  # (name, [param types], ret type) top-level generated functions
        self.classes = []  # (kind, ...) described below
        self.uses_co = False
        self.env = Env()

    # ------------------------------------------------------------------ helpers
    def fresh(self, prefix="v"):
        self.n += 1
        return "%s%d" % (prefix, self.n)

    def pick(self, xs):
        return self.r.choice(xs)

    def chance(self, p):
        return self.r.random() < p

    def lit(self, ty):
        r = self.r
        if ty == "Int":
            v = self.pick(INT_LIT) if self.chance(0.6) else r.randint(-50, 200)
            return "(%d)" % v if v < 0 else str(v)
        if ty == "Long":
            v = self.pick(LONG_LIT) if self.chance(0.5) else r.randint(-1000, 100000)
            if v == -9223372036854775808:
                return "Long.MIN_VALUE"
            return ("(%dL)" if v < 0 else "%dL") % v
        if ty == "Boolean":
            return self.pick(["true", "false"])
        if ty == "String":
            return '"%s"' % self.pick(WORDS)
        if ty == "Char":
            return self.pick(CHARS)
        if ty == "Double":
            return self.pick(["0.5", "1.25", "2.0", "(-3.75)", "100.0", "0.125", "8.0"])
        raise ValueError(ty)

    # ------------------------------------------------------------------ expressions
    def expr(self, ty, d=3, env=None):
        env = env or self.env
        m = getattr(self, "e_" + ty.replace("<", "_").replace(">", "").replace("?", "N").replace(",", "_").replace(" ", ""))
        return m(d, env)

    def var_or_lit(self, ty, env):
        vs = env.of(ty)
        if vs and self.chance(0.7):
            return self.pick(vs)
        return self.lit(ty)

    def e_Int(self, d, env):
        if d <= 0:
            return self.var_or_lit("Int", env)
        r = self.r
        k = r.randint(0, 27)
        s = lambda t, dd=None: self.expr(t, d - 1 if dd is None else dd, env)
        if k <= 3:
            return self.var_or_lit("Int", env)
        if k <= 6:
            return "(%s %s %s)" % (s("Int"), self.pick(["+", "-", "*"]), s("Int"))
        if k == 7:
            return "(%s %s %s)" % (s("Int"), self.pick(["and", "or", "xor"]), s("Int"))
        if k == 8:
            return "(%s %s %d)" % (s("Int"), self.pick(["shl", "shr", "ushr"]), r.randint(0, 40))
        if k == 9:
            return "(%s %s (%s or 1))" % (s("Int"), self.pick(["/", "%"]), s("Int"))
        if k == 10:
            return "(if (%s) %s else %s)" % (s("Boolean"), s("Int"), s("Int"))
        if k == 11 and env.of("List<Int>"):
            return "%s.size" % self.pick(env.of("List<Int>"))
        if k == 12 and env.of("List<Int>"):
            return "%s.sum()" % self.pick(env.of("List<Int>"))
        if k == 13:
            return "%s.length" % s("String")
        if k == 14:
            return "%s.toInt()" % s("Long")
        if k == 15:
            return "%s.code" % s("Char")
        if k == 16:
            return "%s.hashCode()" % s("String")
        if k == 17:
            return "%s.coerceIn(%s, %s)" % (s("Int"), self.lit("Int"), self.pick(["100", "1000", "2147483647"]))
        if k == 18:
            return "%s(%s, %s)" % (self.pick(["maxOf", "minOf"]), s("Int"), s("Int"))
        if k == 19:
            return "(-%s)" % s("Int")
        if k == 20:
            return "%s.compareTo(%s)" % (s("Int"), s("Int"))
        if k == 21 and env.of("IntArray"):
            a = self.pick(env.of("IntArray"))
            return "%s[(%s and 0x7fffffff) %% %s.size]" % (a, s("Int"), a)
        if k == 22 and env.of("List<Int>"):
            a = self.pick(env.of("List<Int>"))
            return "(if (%s.isEmpty()) 0 else %s[(%s and 0x7fffffff) %% %s.size])" % (a, a, s("Int"), a)
        if k == 23:
            return "(%s ?: %s)" % (self.nullable_int(d - 1, env), s("Int"))
        if k == 24 and self.funs:
            f = [f for f in self.funs if f[2] == "Int"]
            if f:
                f = self.pick(f)
                return "%s(%s)" % (f[0], ", ".join(self.expr(t, d - 1, env) for t in f[1]))
        if k == 25 and self.classes:
            c = self.pick(self.classes)
            e = self.class_int_expr(c, d - 1, env)
            if e:
                return e
        if k == 26:
            return self.int_when(d - 1, env)
        if k == 27 and self.lambdas(env, "Int"):
            return self.call_lambda(self.pick(self.lambdas(env, "Int")), d - 1, env)
        return self.var_or_lit("Int", env)

    def lambdas(self, env, ret):
        return [(n, t) for n, (t, _) in sorted(env.all().items()) if t.startswith("(") and t.endswith(")->" + ret)]

    def call_lambda(self, nt, d, env):
        n, t = nt
        params = t[1 : t.index(")->")]
        args = [self.expr(p, d, env) for p in params.split(",") if p]
        return "%s(%s)" % (n, ", ".join(args))

    def int_when(self, d, env):
        subj = self.expr("Int", d, env)
        arms = []
        for _ in range(self.r.randint(1, 3)):
            arms.append("%s -> %s" % (self.pick([self.lit("Int"), "in 1..9", "in 10..99", "%s, %s" % (self.lit("Int"), self.lit("Int")), "!in 0..50"]), self.expr("Int", d - 1, env)))
        arms.append("else -> %s" % self.expr("Int", d - 1, env))
        return "(when (%s) { %s })" % (subj, "; ".join(arms))

    def nullable_int(self, d, env):
        vs = env.of("Int?")
        k = self.r.randint(0, 4)
        if k == 0 and vs:
            return self.pick(vs)
        if k == 1:
            return "(if (%s) null else %s)" % (self.expr("Boolean", d, env), self.expr("Int", d, env))
        if k == 2:
            return "%s.toIntOrNull()" % self.expr("String", d, env)
        if k == 3 and env.of("List<Int>"):
            return "%s.firstOrNull()" % self.pick(env.of("List<Int>"))
        if k == 4 and env.of("Map<String,Int>"):
            return "%s[%s]" % (self.pick(env.of("Map<String,Int>")), self.expr("String", 0, env))
        return "null"

    def e_Int_(self, d, env):  # Int? (e_IntN after mangling is handled below)
        return self.nullable_int(d, env)

    e_IntN = e_Int_

    def e_Long(self, d, env):
        if d <= 0:
            return self.var_or_lit("Long", env)
        r = self.r
        k = r.randint(0, 11)
        s = lambda t: self.expr(t, d - 1, env)
        if k <= 2:
            return self.var_or_lit("Long", env)
        if k <= 4:
            return "(%s %s %s)" % (s("Long"), self.pick(["+", "-", "*"]), s("Long"))
        if k == 5:
            return "%s.toLong()" % s("Int")
        if k == 6:
            return "(%s %s %d)" % (s("Long"), self.pick(["shl", "shr", "ushr"]), r.randint(0, 70))
        if k == 7:
            return "(%s %s (%s or 1L))" % (s("Long"), self.pick(["/", "%"]), s("Long"))
        if k == 8:
            return "(%s %s %s)" % (s("Long"), self.pick(["and", "or", "xor"]), s("Long"))
        if k == 9:
            return "(if (%s) %s else %s)" % (s("Boolean"), s("Long"), s("Long"))
        if k == 10:
            return "%s.toLong()" % s("Char")
        return "%s.toLong()" % s("Int")

    def e_Double(self, d, env):
        if d <= 0:
            return self.var_or_lit("Double", env)
        k = self.r.randint(0, 4)
        s = lambda t: self.expr(t, d - 1, env)
        if k == 0:
            return self.var_or_lit("Double", env)
        if k == 1:
            return "(%s %s %s)" % (s("Double"), self.pick(["+", "-", "*"]), s("Double"))
        if k == 2:
            return "(%s.toDouble() / %d)" % (s("Int"), self.pick([2, 4, 8, 16]))
        if k == 3:
            return "(%s / 4.0)" % s("Double")
        return "(%s.coerceIn(-1000.0, 1000.0))" % s("Double")

    def e_Boolean(self, d, env):
        if d <= 0:
            return self.var_or_lit("Boolean", env)
        r = self.r
        k = r.randint(0, 14)
        s = lambda t: self.expr(t, d - 1, env)
        if k <= 1:
            return self.var_or_lit("Boolean", env)
        if k <= 3:
            return "(%s %s %s)" % (s("Int"), self.pick(["<", "<=", ">", ">=", "==", "!="]), s("Int"))
        if k == 4:
            return "(%s %s %s)" % (s("Boolean"), self.pick(["&&", "||", "xor"]), s("Boolean"))
        if k == 5:
            return "(!%s)" % s("Boolean")
        if k == 6:
            return "(%s %s %s)" % (s("String"), self.pick(["==", "!=", "<", ">="]), s("String"))
        if k == 7:
            return "%s.%s(%s)" % (s("String"), self.pick(["startsWith", "endsWith", "contains"]), s("String"))
        if k == 8:
            return "(%s %s %s)" % (s("Long"), self.pick(["<", ">", "==", "!="]), s("Long"))
        if k == 9 and env.of("List<Int>"):
            return "(%s in %s)" % (s("Int"), self.pick(env.of("List<Int>")))
        if k == 10:
            return "(%s in %d..%d)" % (s("Int"), r.randint(-5, 5), r.randint(6, 90))
        if k == 11:
            return "%s.isEmpty()" % s("String")
        if k == 12:
            return "(%s %s %s)" % (s("Char"), self.pick(["<", "==", ">="]), s("Char"))
        if k == 13:
            return "%s.isLetter()" % s("Char")
        return "(%s == null)" % self.nullable_int(d - 1, env)

    def e_Char(self, d, env):
        k = self.r.randint(0, 4)
        if d <= 0 or k == 0:
            return self.var_or_lit("Char", env)
        s = lambda t: self.expr(t, d - 1, env)
        if k == 1:
            return "(%s + %d)" % (s("Char"), self.r.randint(0, 5))
        if k == 2:
            return "%s.uppercaseChar()" % s("Char")
        if k == 3:
            sv = self.pick(env.of("String") or ['"z"'])
            return "(if (%s.isEmpty()) 'e' else %s[0])" % (sv, sv)
        return "(%s %% 26 + 97).toChar()" % ("(" + s("Int") + " and 0x7fffffff)")

    def e_String(self, d, env):
        if d <= 0:
            return self.var_or_lit("String", env)
        r = self.r
        k = r.randint(0, 22)
        s = lambda t: self.expr(t, d - 1, env)
        if k <= 2:
            return self.var_or_lit("String", env)
        if k <= 4:
            return "(%s + %s)" % (s("String"), s("String"))
        if k == 5:
            return "%s.toString()" % s("Int")
        if k == 6:
            return '"${%s}:${%s}"' % (s("Int"), s("String"))
        if k == 7:
            return "%s.repeat(%s and 3)" % (s("String"), s("Int"))
        if k == 8:
            return "%s.uppercase()" % s("String")
        if k == 9:
            return "%s.reversed()" % s("String")
        if k == 10:
            return "%s.take(%s and 7)" % (s("String"), s("Int"))
        if k == 11:
            return "%s.drop(%s and 3)" % (s("String"), s("Int"))
        if k == 12:
            return "%s.padStart(%d, '%s')" % (s("String"), r.randint(0, 8), self.pick(["0", "*", " "]))
        if k == 13:
            return "%s.trim()" % s("String")
        if k == 14:
            return "%s.replace(%s, %s)" % (s("String"), self.lit("String"), s("String"))
        if k == 15 and env.of("List<Int>"):
            return '%s.joinToString("%s")' % (self.pick(env.of("List<Int>")), self.pick([",", "-", "", ", "]))
        if k == 16:
            return "%s.toString()" % s("Long")
        if k == 17:
            return "%s.toString()" % s("Char")
        if k == 18:
            return "(if (%s) %s else %s)" % (s("Boolean"), s("String"), s("String"))
        if k == 19:
            return '(%s?.toString() ?: "null")' % self.nullable_int(d - 1, env)
        if k == 20:
            return "%s.lowercase()" % s("String")
        if k == 21:
            return "%s.substringBefore(%s)" % (s("String"), self.lit("String"))
        if k == 22 and self.classes:
            c = self.pick(self.classes)
            if c["kind"] in ("data", "class"):
                return "%s.toString()" % self.new_obj(c, d - 1, env)
        return self.var_or_lit("String", env)

    def e_List_Int(self, d, env):
        if d <= 0:
            return self.var_or_lit_list(env)
        r = self.r
        k = r.randint(0, 17)
        s = lambda t: self.expr(t, d - 1, env)
        ls = env.of("List<Int>")
        src = lambda: self.pick(ls) if ls else self.list_lit(d - 1, env)
        if k <= 1:
            return self.var_or_lit_list(env)
        if k == 2:
            return self.list_lit(d - 1, env)
        if k == 3:
            return "%s.map { %s }" % (src(), self.lambda_body("it", d - 1, env))
        if k == 4:
            return "%s.filter { %s }" % (src(), self.pred_body("it", d - 1, env))
        if k == 5:
            return "%s.sorted()" % src()
        if k == 6:
            return "%s.reversed()" % src()
        if k == 7:
            return "(%s + %s)" % (src(), src())
        if k == 8:
            return "%s.take(%s and 3)" % (src(), s("Int"))
        if k == 9:
            return "%s.drop(%s and 3)" % (src(), s("Int"))
        if k == 10:
            return "%s.distinct()" % src()
        if k == 11:
            return "%s.sortedBy { %s }" % (src(), self.lambda_body("it", d - 1, env))
        if k == 12:
            return "%s.zip(%s) { a, b -> a * 3 + b }" % (src(), src())
        if k == 13:
            return "%s.mapIndexed { i, x -> x + i * %d }" % (src(), r.randint(1, 9))
        if k == 14:
            return "(0 until (%s and 7)).toList()" % s("Int")
        if k == 15:
            return "%s.flatMap { listOf(it, it + 1) }" % src()
        if k == 16:
            return "%s.windowed(2).map { it.sum() }" % src()
        return "%s.sortedDescending()" % src()

    e_List_Int_ = e_List_Int

    def var_or_lit_list(self, env):
        vs = env.of("List<Int>")
        if vs and self.chance(0.7):
            return self.pick(vs)
        return self.list_lit(0, env)

    def list_lit(self, d, env):
        n = self.r.randint(0, 6)
        return "listOf(%s)" % ", ".join(self.expr("Int", max(d, 0), env) for _ in range(n)) if n else "emptyList<Int>()"

    def lambda_body(self, p, d, env):
        e = Env(env)
        e.add(p, "Int")
        return self.expr("Int", d, e)

    def pred_body(self, p, d, env):
        e = Env(env)
        e.add(p, "Int")
        return self.expr("Boolean", d, e)

    def e_IntArray(self, d, env):
        vs = env.of("IntArray")
        if vs and self.chance(0.5):
            return self.pick(vs)
        return "IntArray(%d) { it * %s + %s }" % (self.r.randint(1, 8), self.lit("Int"), self.lit("Int"))

    def e_Map_String_Int(self, d, env):
        vs = env.of("Map<String,Int>")
        if vs and self.chance(0.5):
            return self.pick(vs)
        n = self.r.randint(0, 4)
        return "mapOf(%s)" % ", ".join('"%s" to %s' % (self.pick(WORDS[:8]), self.expr("Int", max(d - 1, 0), env)) for _ in range(n))

    # ------------------------------------------------------------------ declarations (classes, interfaces, generics, objects, extensions)
    def make_decls(self):
        r = self.r
        self.classes = []
        parts = []

        # interface + implementations
        if self.chance(0.85):
            iface = self.fresh("I")
            impls = []
            lines = ["interface %s {" % iface, "    fun score(x: Int): Int", '    fun tag(): String = "%s"' % iface, "}"]
            parts.append(lines)
            for i in range(r.randint(1, 3)):
                cn = self.fresh("C")
                body = self.expr("Int", 2, self.param_env(["x", "k"]))
                e2 = Env()
                e2.add("x", "Int")
                e2.add("k", "Int")
                body = self.expr("Int", 2, e2)
                tagov = '    override fun tag(): String = "%s" + k' % cn if self.chance(0.5) else None
                lines = ["class %s(val k: Int) : %s {" % (cn, iface), "    override fun score(x: Int): Int = %s" % body]
                if tagov:
                    lines.append(tagov)
                lines.append("}")
                parts.append(lines)
                impls.append(cn)
            self.classes.append({"kind": "iface", "name": iface, "impls": impls})

        # open class hierarchy with overrides and super calls
        if self.chance(0.9):
            base = self.fresh("B")
            der = self.fresh("D")
            e1 = Env()
            e1.add("x", "Int")
            e1.add("id", "Int")
            lines = [
                "open class %s(val id: Int) {" % base,
                "    open fun f(x: Int): Int = %s" % self.expr("Int", 2, e1),
                '    open fun name(): String = "%s" + id' % base,
                "    override fun toString(): String = name() + \"/\" + f(id)",
                "}",
            ]
            parts.append(lines)
            e2 = Env()
            e2.add("x", "Int")
            e2.add("id", "Int")
            e2.add("k", "Int")
            lines = [
                "class %s(id: Int, val k: Int) : %s(id) {" % (der, base),
                "    override fun f(x: Int): Int = %s(super.f(x), %s)" % (self.pick(["maxOf", "minOf"]), self.expr("Int", 2, e2)),
                '    override fun name(): String = "%s" + k + super.name()' % der,
                "}",
            ]
            parts.append(lines)
            # abstract class
            ab = self.fresh("A")
            lines = ["abstract class %s(val w: Int) {" % ab, "    abstract fun calc(x: Int): Int", "    fun twice(x: Int): Int = calc(calc(x))", "}"]
            parts.append(lines)
            self.classes.append({"kind": "class", "name": base, "sub": der, "abs": ab})

        # data class
        if self.chance(0.9):
            dc = self.fresh("P")
            lines = ["data class %s(val a: Int, val b: String, var c: Long = %s)" % (dc, self.lit("Long"))]
            parts.append(lines)
            self.classes.append({"kind": "data", "name": dc})

        # sealed class + when
        if self.chance(0.8):
            sc = self.fresh("S")
            a, b = self.fresh("Sa"), self.fresh("Sb")
            lines = [
                "sealed class %s" % sc,
                "class %s(val n: Int) : %s()" % (a, sc),
                "class %s(val s: String, val m: Int) : %s()" % (b, sc),
                "object %sNone : %s()" % (sc, sc),
                "fun describe%s(x: %s): String = when (x) {" % (sc, sc),
                '    is %s -> "a" + x.n' % a,
                '    is %s -> "b" + x.s + x.m' % b,
                '    %sNone -> "none"' % sc,
                "}",
            ]
            parts.append(lines)
            self.classes.append({"kind": "sealed", "name": sc, "a": a, "b": b})

        # enum
        if self.chance(0.6):
            en = self.fresh("E")
            ks = ["K%d" % i for i in range(r.randint(2, 4))]
            lines = ["enum class %s(val w: Int) {" % en, "    " + ", ".join("%s(%s)" % (k, r.randint(1, 20)) for k in ks) + ";", "    fun bump(x: Int): Int = x * w + ordinal", "}"]
            parts.append(lines)
            self.classes.append({"kind": "enum", "name": en, "ks": ks})

        # generic class and function
        if self.chance(0.85):
            g = self.fresh("G")
            lines = [
                "class %s<T>(val v: T) {" % g,
                "    fun <R> map(f: (T) -> R): %s<R> = %s(f(v))" % (g, g),
                "    override fun toString(): String = \"%s(\" + v + \")\"" % g,
                "}",
                "fun <T : Comparable<T>> best%s(a: T, b: T, c: T): T = if (a >= b) (if (a >= c) a else c) else (if (b >= c) b else c)" % g,
            ]
            parts.append(lines)
            self.classes.append({"kind": "generic", "name": g})

        # object singleton
        if self.chance(0.7):
            ob = self.fresh("O")
            lines = ["object %s {" % ob, "    var n = %s" % self.lit("Int"), "    val log = ArrayList<Int>()", "    fun next(by: Int): Int { n += by; log.add(n); return n }", "}"]
            parts.append(lines)
            self.classes.append({"kind": "object", "name": ob})

        # extension functions
        if self.chance(0.85):
            e1 = Env()
            e1.add("this", "Int")
            e1.add("o", "Int")
            nm = self.fresh("ext")
            lines = ["fun Int.%sA(o: Int): Int = %s" % (nm, self.expr("Int", 2, self.ext_env("Int")).replace("this", "this")), "fun String.%sB(n: Int): String = this.take(n and 3) + this.length" % nm, "fun List<Int>.%sC(): Int = fold(7) { a, x -> a * 31 + x }" % nm]
            parts.append(lines)
            self.classes.append({"kind": "ext", "name": nm})


        # --- feature bank: object graphs with cycles, operators, delegation, companions, inner classes, exceptions, varargs ---
        if self.chance(0.7):
            nn = self.fresh("N")
            lines = [
                "class %s(var v: Int) {" % nn,
                "    var next: %s? = null" % nn,
                "    var back: %s? = null" % nn,
                "    val kids = ArrayList<%s>()" % nn,
                "    fun sum(depth: Int): Int { var s = v; if (depth > 0) { for (k in kids) s += k.sum(depth - 1) * 3 }; return s }",
                "}",
                "fun ring%s(n: Int, base: Int): %s {" % (nn, nn),
                "    val first = %s(base)" % nn,
                "    var cur = first",
                "    for (i in 1 until n) { val x = %s(base + i); cur.next = x; x.back = cur; cur.kids.add(x); cur = x }" % nn,
                "    cur.next = first; first.back = cur",
                "    return first",
                "}",
            ]
            parts.append(lines)
            self.classes.append({"kind": "graph", "name": nn})
        if self.chance(0.7):
            vc = self.fresh("V")
            lines = [
                "data class %s(val x: Int, val y: Int) : Comparable<%s> {" % (vc, vc),
                "    operator fun plus(o: %s) = %s(x + o.x, y + o.y)" % (vc, vc),
                "    operator fun times(k: Int) = %s(x * k, y * k)" % vc,
                "    operator fun unaryMinus() = %s(-x, -y)" % vc,
                "    operator fun get(i: Int) = if (i == 0) x else y",
                "    operator fun invoke(k: Int) = x * k + y",
                "    override fun compareTo(other: %s): Int = (x * 31 + y).compareTo(other.x * 31 + other.y)" % vc,
                "    companion object { val ZERO = %s(0, 0); fun of(a: Int) = %s(a, a + 1) }" % (vc, vc),
                "}",
            ]
            parts.append(lines)
            self.classes.append({"kind": "vec", "name": vc})
        if self.chance(0.6) and any(c["kind"] == "iface" for c in self.classes):
            ic = [c for c in self.classes if c["kind"] == "iface"][0]
            wn = self.fresh("W")
            lines = [
                "class %s(private val inner: %s, val extra: Int) : %s by inner {" % (wn, ic["name"], ic["name"]),
                "    val lazyVal: Int by lazy { extra * 2 + 1 }",
                "    var watched: Int = 0",
                "        set(v) { field = v + extra }",
                "    inner class In(val q: Int) { fun both(x: Int) = x + q + extra }",
                "}",
            ]
            parts.append(lines)
            self.classes.append({"kind": "deleg", "name": wn, "iface": ic})
        if self.chance(0.7):
            ex = self.fresh("X")
            lines = [
                "open class %s(msg: String, val code: Int, cause: Throwable? = null) : Exception(msg, cause)" % ex,
                "class %sSub(msg: String, code: Int, cause: Throwable?) : %s(msg, code, cause)" % (ex, ex),
                "fun thrower%s(n: Int): Int {" % ex,
                "    try {",
                "        if (n %% 3 == 0) throw %s(\"t\" + n, n)" % ex,
                "        if (n %% 3 == 1) throw %sSub(\"s\" + n, n * 2, IllegalStateException(\"c\"))" % ex,
                "        return n",
                "    } finally {",
                "        println(\"cleanup\" + n)",
                "    }",
                "}",
            ]
            parts.append(lines)
            self.classes.append({"kind": "exc", "name": ex})
        if self.chance(0.7):
            vf = self.fresh("misc")
            lines = [
                "fun %sVar(vararg xs: Int, scale: Int = 2, bias: Int = 1): Int { var s = bias; for (x in xs) s = s * 31 + x * scale; return s }" % vf,
                "inline fun <T> %sTwice(f: () -> T): Pair<T, T> = Pair(f(), f())" % vf,
                "tailrec fun %sGcd(a: Int, b: Int): Int = if (b == 0) a else %sGcd(b, a %% b)" % (vf, vf),
                "typealias %sPair = Pair<Int, String>" % vf,
            ]
            parts.append(lines)
            self.classes.append({"kind": "misc", "name": vf})

        # top-level functions with statements (no recursion into later ones)
        for _ in range(r.randint(1, 4)):
            self.gen_function(parts)

        # bounded recursion
        if self.chance(0.6):
            rf = self.fresh("rec")
            lines = ["fun %s(n: Int, acc: Int): Int = if (n <= 0) acc else %s(n - 1, %s)" % (rf, rf, self.pick(["acc * 31 + n", "acc + n * n", "acc xor (n shl 3)", "(acc ushr 1) + n"]))]
            parts.append(lines)
            self.funs.append((rf + "B", ["Int", "Int"], "Int"))
            parts.append(["fun %sB(a: Int, b: Int): Int = %s(a and 15, b)" % (rf, rf)])

        # suspend functions
        if self.chance(0.35):
            self.uses_co = True
            sf = self.fresh("susp")
            lines = [
                "suspend fun %s(x: Int): Int {" % sf,
                "    val y = coStep(x)",
                "    return if (y %% 2 == 0) y + %s else coStep(y) * 3" % self.lit("Int"),
                "}",
            ]
            parts.append(lines)
            self.classes.append({"kind": "susp", "name": sf})
        return parts

    def param_env(self, names):
        e = Env()
        for n in names:
            e.add(n, "Int")
        return e

    def ext_env(self, ty):
        e = Env()
        e.add("o", "Int")
        return _ExtEnv(e)

    def gen_function(self, parts):
        name = self.fresh("fn")
        n = self.r.randint(1, 3)
        types = [self.pick(["Int", "Int", "Long", "String", "List<Int>"]) for _ in range(n)]
        ret = self.pick(["Int", "Int", "Long", "String"])
        env = Env()
        pnames = []
        for i, t in enumerate(types):
            pn = "p%d" % i
            env.add(pn, t)
            pnames.append("%s: %s" % (pn, t))
        saved = self.env
        self.env = env
        body = []
        self.stmts(body, self.r.randint(2, 5), env, 2, in_fun=True)
        body.append("return " + self.expr(ret, 3, env))
        self.env = saved
        lines = ["fun %s(%s): %s {" % (name, ", ".join(pnames), ret)] + ["    " + l for l in body] + ["}"]
        parts.append(lines)
        self.funs.append((name, types, ret))

    def new_obj(self, c, d, env):
        if c["kind"] == "data":
            return "%s(%s, %s)" % (c["name"], self.expr("Int", d, env), self.expr("String", d, env))
        if c["kind"] == "class":
            if self.chance(0.5):
                return "%s(%s)" % (c["name"], self.expr("Int", d, env))
            return "%s(%s, %s)" % (c["sub"], self.expr("Int", d, env), self.expr("Int", d, env))
        raise ValueError

    def class_int_expr(self, c, d, env):
        k = c["kind"]
        if k == "iface":
            impl = self.pick(c["impls"])
            return "%s(%s).score(%s)" % (impl, self.expr("Int", d, env), self.expr("Int", d, env))
        if k == "class":
            return "%s.f(%s)" % (self.new_obj(c, d, env), self.expr("Int", d, env))
        if k == "data":
            return "%s.a" % self.new_obj(c, d, env)
        if k == "enum":
            return "%s.%s.bump(%s)" % (c["name"], self.pick(c["ks"]), self.expr("Int", d, env))
        if k == "object":
            return "%s.next(%s and 15)" % (c["name"], self.expr("Int", d, env))
        if k == "ext":
            return "%s.%sA(%s)" % (self.expr("Int", d, env), c["name"], self.expr("Int", d, env))
        if k == "generic":
            return "%s(%s).map { it + 1 }.v" % (c["name"], self.expr("Int", d, env))
        if k == "sealed":
            return "describe%s(%s).length" % (c["name"], self.sealed_obj(c, d, env))
        return None

    def sealed_obj(self, c, d, env):
        k = self.r.randint(0, 2)
        if k == 0:
            return "%s(%s)" % (c["a"], self.expr("Int", d, env))
        if k == 1:
            return "%s(%s, %s)" % (c["b"], self.expr("String", d, env), self.expr("Int", d, env))
        return "%sNone" % c["name"]

    # ------------------------------------------------------------------ statements
    def stmts(self, out, n, env, depth, in_fun=False):
        for _ in range(n):
            self.stmt(out, env, depth, in_fun)

    def show(self, e):
        return "println(%s)" % e

    def stmt(self, out, env, depth, in_fun):
        r = self.r
        k = r.randint(0, 33)
        ex = lambda t, d=3: self.expr(t, d, env)
        if k <= 3:
            ty = self.pick(["Int", "Int", "Long", "String", "Boolean", "List<Int>", "Char", "Double"])
            v = self.fresh()
            mut = self.chance(0.5)
            out.append("%s %s: %s = %s" % ("var" if mut else "val", v, ty, ex(ty)))
            env.add(v, ty, mut)
            return
        if k == 4:
            ty = self.pick(["Int", "Long", "String"])
            ms = env.mutable(ty)
            if ms:
                v = self.pick(ms)
                op = self.pick(["=", "="] + (["+="] if ty != "Boolean" else []))
                out.append("%s %s %s" % (v, op, ex(ty)))
                return
        if k == 5:
            out.append(self.show(ex(self.pick(["Int", "Long", "String", "Boolean", "List<Int>", "Char", "Double"]))))
            return
        if k == 6 and depth > 0:
            c = ex("Boolean", 2)
            out.append("if (%s) {" % c)
            b = []
            self.stmts(b, r.randint(1, 3), Env(env), depth - 1, in_fun)
            out.extend("    " + l for l in b)
            if self.chance(0.6):
                out.append("} else {")
                b = []
                self.stmts(b, r.randint(1, 3), Env(env), depth - 1, in_fun)
                out.extend("    " + l for l in b)
            out.append("}")
            return
        if k == 7 and depth > 0:
            i = self.fresh("i")
            e = Env(env)
            e.add(i, "Int")
            out.append("for (%s in %s) {" % (i, self.pick(["0 until %d" % r.randint(0, 6), "1..%d" % r.randint(1, 5), "%d downTo 0" % r.randint(0, 4), "0 until %d step 2" % r.randint(2, 9)])))
            b = []
            cond = self.expr("Boolean", 2, Env(e))
            self.stmts(b, r.randint(1, 3), e, depth - 1, in_fun)
            if self.chance(0.3):
                # only between statements (brace depth 0), with a condition over what is visible there
                spots, dep = [], 0
                for n_, l_ in enumerate(b + [""]):
                    if dep == 0:
                        spots.append(n_)
                    dep += l_.count("{") - l_.count("}")
                b.insert(self.pick(spots), "if (%s) %s" % (cond, self.pick(["continue", "break"])))
            out.extend("    " + l for l in b)
            out.append("}")
            return
        if k == 8 and depth > 0:
            ls = env.of("List<Int>")
            if ls:
                x = self.fresh("x")
                e = Env(env)
                e.add(x, "Int")
                out.append("for (%s in %s) {" % (x, self.pick(ls)))
                b = []
                self.stmts(b, r.randint(1, 2), e, depth - 1, in_fun)
                out.extend("    " + l for l in b)
                out.append("}")
                return
        if k == 9 and depth > 0:
            cnt = self.fresh("w")
            out.append("var %s = 0" % cnt)
            e = Env(env)
            out.append("while (%s < %d) {" % (cnt, r.randint(1, 5)))
            b = []
            self.stmts(b, r.randint(1, 2), e, depth - 1, in_fun)
            out.extend("    " + l for l in b)
            out.append("    %s++" % cnt)
            out.append("}")
            return
        if k == 10:
            out.append(self.show("%s" % self.int_when(2, env)))
            return
        if k == 11 and depth > 0:
            # try/catch/finally with throws
            out.append("try {")
            b = []
            self.stmts(b, 1, Env(env), 0, in_fun)
            b.append(self.pick([
                "println(%s)" % ex("Int", 2),
                "throw IllegalStateException(%s)" % ex("String", 1),
                "println(%s / %s)" % (ex("Int", 1), ex("Int", 1)),
                "println(listOf(1, 2, 3)[%s and 7])" % ex("Int", 1),
                "println(%s.toInt())" % ex("String", 1),
                "check(%s) { %s }" % (ex("Boolean", 1), ex("String", 1)),
                "require(%s)" % ex("Boolean", 1),
                "println(IntArray(3)[%s and 7])" % ex("Int", 1),
                "println((%s as Any) as Int)" % ex("String", 1),
                "error(%s)" % ex("String", 1),
            ]))
            out.extend("    " + l for l in b)
            catches = [
                ("ArithmeticException", '"arith"'),
                ("IndexOutOfBoundsException", '"index"'),
                ("NumberFormatException", '"nfe"'),
                ("IllegalStateException", '"ise:" + e.message'),
                ("IllegalArgumentException", '"iae"'),
                ("ClassCastException", '"cce"'),
            ]
            used = set()
            # order matters: subclasses first (NumberFormatException < IllegalArgumentException; ISE and IAE unrelated)
            for name, msg in catches:
                if self.chance(0.6):
                    out.append("} catch (e: %s) {" % name)
                    out.append("    println(%s)" % msg)
                    used.add(name)
            out.append("} catch (e: Exception) {")
            out.append('    println("other")')
            if self.chance(0.4):
                out.append("} finally {")
                out.append('    println("fin")')
            out.append("}")
            return
        if k == 12:
            ls = env.of("List<Int>")
            ty = "Int"
            v = self.fresh("l")
            out.append("val %s = mutableListOf<Int>(%s)" % (v, ", ".join(ex("Int", 1) for _ in range(r.randint(0, 4)))))
            env.add(v, "List<Int>")
            out.append("%s.add(%s)" % (v, ex("Int", 2)))
            if self.chance(0.5):
                out.append("if (%s.size > 1) %s.removeAt(%s and 1)" % (v, v, ex("Int", 1)))
            if self.chance(0.5):
                out.append("%s.sort()" % v)
            if self.chance(0.4):
                out.append("%s.addAll(%s)" % (v, ex("List<Int>", 2)))
            if self.chance(0.3):
                out.append("%s.removeAll { it %% 2 == 0 }" % v)
            return
        if k == 13:
            v = self.fresh("a")
            out.append("val %s = IntArray(%d) { it * %s + %s }" % (v, r.randint(1, 8), self.lit("Int"), self.lit("Int")))
            out.append("%s[%s %% %s.size] = %s" % (v, "(" + ex("Int", 1) + " and 0x7fffffff)", v, ex("Int", 2)))
            if self.chance(0.5):
                out.append("%s.sort()" % v)
            out.append(self.show("%s.contentToString()" % v))
            out.append(self.show("%s.sum() + %s.max()" % (v, v)))
            env.add(v, "IntArray")
            return
        if k == 14:
            v = self.fresh("m")
            out.append("val %s = mutableMapOf<String, Int>()" % v)
            for _ in range(r.randint(1, 4)):
                out.append('%s[%s] = (%s[%s] ?: 0) + %s' % (v, self.lit("String"), v, self.lit("String"), ex("Int", 2)))
            if self.chance(0.5):
                out.append("%s.remove(%s)" % (v, self.lit("String")))
            out.append(self.show(v))
            out.append(self.show("%s.entries.sumOf { it.value * it.key.length }" % v))
            out.append(self.show("%s.keys.sorted()" % v))
            env.add(v, "Map<String,Int>")
            return
        if k == 15:
            v = self.fresh("sb")
            out.append("val %s = StringBuilder()" % v)
            for _ in range(r.randint(1, 4)):
                out.append(self.pick(["%s.append(%s)" % (v, ex("String", 2)), "%s.append(%s)" % (v, ex("Int", 2)), "%s.append(%s)" % (v, ex("Char", 1)), "%s.insert(0, %s)" % (v, ex("String", 1))]))
            out.append(self.show("%s.toString()" % v))
            out.append(self.show("%s.length" % v))
            if self.chance(0.4):
                out.append("%s.setLength(%s.length / 2)" % (v, v))
                out.append(self.show("%s.toString()" % v))
            return
        if k == 16:
            # lambdas and closures capturing a mutable variable
            cnt = self.fresh("cnt")
            f = self.fresh("f")
            g = self.fresh("g")
            out.append("var %s = %s" % (cnt, ex("Int", 1)))
            e = Env(env)
            e.add("a", "Int")
            body = self.expr("Int", 2, e)
            out.append("val %s: (Int) -> Int = { a -> %s += a and 7; %s }" % (f, cnt, body))
            out.append("val %s: (Int) -> Int = { a -> %s + a }" % (g, cnt))
            for _ in range(r.randint(1, 3)):
                out.append(self.show("%s(%s)" % (self.pick([f, g]), ex("Int", 1))))
            out.append(self.show(cnt))
            env.add(f, "(Int)->Int")
            env.add(g, "(Int)->Int")
            return
        if k == 17:
            # sequence builder (coroutine)
            v = self.fresh("sq")
            i = self.fresh("i")
            out.append("val %s = sequence {" % v)
            out.append("    var %s = %s" % (i, ex("Int", 1)))
            out.append("    for (j in 0 until %d) {" % r.randint(1, 6))
            out.append("        %s = %s" % (i, self.expr("Int", 1, self._with(env, i, "Int"))))
            out.append("        yield(%s)" % i)
            if self.chance(0.5):
                out.append("        if (%s %% 3 == 0) yieldAll(listOf(j, j + 1))" % ("(" + i + " and 0x7fffffff)"))
            out.append("    }")
            out.append("}")
            out.append(self.show("%s.take(%d).toList()" % (v, r.randint(1, 8))))
            out.append(self.show("%s.map { it * 2 }.filter { it %% 3 != 0 }.toList().size" % v))
            return
        if k == 18:
            v = self.fresh("gs")
            out.append("val %s = generateSequence(%s) { if (it > %d || it < -%d) null else it * 2 + 1 }" % (v, ex("Int", 1) + " and 1023", r.randint(10, 500), r.randint(10, 500)))
            out.append(self.show("%s.toList()" % v))
            return
        if k == 19 and self.classes:
            c = self.pick(self.classes)
            self.class_stmt(out, c, env)
            return
        if k == 20:
            v = self.fresh("n")
            out.append("var %s: Int? = %s" % (v, self.nullable_int(2, env)))
            env.add(v, "Int?", True)
            out.append(self.show("%s" % v))
            out.append(self.show("%s?.plus(1)" % v))
            out.append(self.show("(%s ?: -1) + (%s?.let { it * 2 } ?: 0)" % (v, v)))
            out.append("if (%s != null) println(%s + 1)" % (v, v))
            return
        if k == 21:
            s = self.fresh("s")
            out.append("val %s: String? = if (%s) %s else null" % (s, ex("Boolean", 2), ex("String", 2)))
            out.append(self.show("%s?.length" % s))
            out.append(self.show("%s?.uppercase() ?: \"-\"" % s))
            out.append("%s?.let { println(it + \"!\") }" % s)
            return
        if k == 22:
            ls = env.of("List<Int>")
            src = self.pick(ls) if ls else self.list_lit(1, env)
            out.append(self.show("%s.fold(0) { a, x -> a * 31 + x }" % src))
            out.append(self.show("%s.groupBy { it %% 3 }" % src))
            out.append(self.show("%s.partition { it > 5 }" % src))
            out.append(self.show("%s.maxOrNull()" % src))
            out.append(self.show("%s.associateWith { it * it }" % src))
            return
        if k == 23:
            v = self.fresh("st")
            out.append("val %s = setOf<Int>(%s)" % (v, ", ".join(ex("Int", 1) for _ in range(r.randint(0, 5)))))
            out.append(self.show("%s.size" % v))
            out.append(self.show("%s.sorted()" % v))
            out.append(self.show("%s + %s" % (v, ex("Int", 1))))
            return
        if k == 24:
            # chk: a checksum over several values
            vs = [n for n in sorted(env.all()) if env.all()[n][0] in ("Int", "Long", "String", "Boolean", "Char")]
            if vs:
                ck = self.fresh("chk")
                out.append("var %s = 17L" % ck)
                for n in vs[:8]:
                    out.append("%s = %s * 31 + %s.hashCode()" % (ck, ck, n))
                out.append(self.show(ck))
                return
        if k == 25 and self.uses_co and not in_fun:
            c = [c for c in self.classes if c["kind"] == "susp"]
            if c:
                nm = c[0]["name"]
                out.append('coLaunch("%s") { %s(%s) + %s(%s) }' % (self.fresh("co"), nm, ex("Int", 1), nm, ex("Int", 1)))
                out.append("coResumeAll()")
                return
        if k == 26:
            out.append(self.show('"%s=" + %s' % (self.pick(["a", "b", "r"]), ex("Double"))))
            out.append(self.show("(%s).toInt()" % ex("Double", 2)))
            return
        if k == 27:
            out.append(self.show("%s.toString(%d)" % (ex("Int", 2), self.pick([2, 8, 16, 36]))))
            out.append(self.show("%s.toString(%d)" % (ex("Long", 2), self.pick([2, 10, 16]))))
            return
        if k == 28:
            out.append(self.show("%s.toByte()" % ex("Int", 2)))
            out.append(self.show("%s.toShort()" % ex("Int", 2)))
            return
        if k == 29:
            out.append(self.show("%s.split(\"%s\")" % (ex("String", 2), self.pick([" ", "a", "b", ","]))))
            out.append(self.show("%s.indexOf(%s)" % (ex("String", 2), ex("String", 1))))
            return
        if k == 30:
            out.append(self.show("%s.compareTo(%s)" % (ex("String", 2), ex("String", 2))))
            out.append(self.show("%s.toCharArray().sortedArray().joinToString(\"\")" % ex("String", 2)))
            return
        if k == 31:
            out.append(self.show("%s.chunked(%d)" % (ex("List<Int>", 2), r.randint(1, 3))))
            out.append(self.show("%s.take(3).zip(%s.take(3))" % (ex("List<Int>", 2), ex("List<Int>", 2))))
            return
        if k == 32:
            out.append(self.show("%s.toLong() * %s" % (ex("Int", 2), ex("Long", 2))))
            out.append(self.show("%s.toInt().toChar().code" % ex("Int", 1) if False else "%s.toChar().code" % ("(" + ex("Int", 1) + " and 0xffff)")))
            return
        out.append(self.show(ex("Int")))

    def _with(self, env, name, ty):
        e = Env(env)
        e.add(name, ty)
        return e

    def class_stmt(self, out, c, env):
        k = c["kind"]
        r = self.r
        ex = lambda t, d=2: self.expr(t, d, env)
        if k == "iface":
            vs = [self.pick(c["impls"]) for _ in range(r.randint(1, 3))]
            v = self.fresh("o")
            out.append("val %s: List<%s> = listOf(%s)" % (v, c["name"], ", ".join("%s(%s)" % (n, ex("Int", 1)) for n in vs)))
            out.append(self.show("%s.map { it.score(%s) }" % (v, ex("Int", 1))))
            out.append(self.show("%s.map { it.tag() }" % v))
        elif k == "class":
            v = self.fresh("b")
            out.append("val %s: %s = %s" % (v, c["name"], self.new_obj(c, 1, env)))
            out.append(self.show("%s" % v))
            out.append(self.show("%s.f(%s)" % (v, ex("Int", 1))))
            out.append(self.show("%s is %s" % (v, c["sub"])))
            ab = c["abs"]
            out.append("val %s = object : %s(%s) { override fun calc(x: Int): Int = %s }" % (self.fresh("ab"), ab, ex("Int", 1), self.expr("Int", 2, self._with(self._with(env, "x", "Int"), "w", "Int"))))
        elif k == "data":
            v = self.fresh("d")
            out.append("val %s = %s" % (v, self.new_obj(c, 2, env)))
            out.append(self.show(v))
            out.append(self.show("%s.copy(a = %s, c = %s)" % (v, ex("Int", 1), ex("Long", 1))))
            out.append(self.show("%s == %s.copy()" % (v, v)))
            out.append(self.show("%s.hashCode() == %s.copy().hashCode()" % (v, v)))
            da, db, dc = self.fresh("da"), self.fresh("db"), self.fresh("dc")
            out.append("val (%s, %s, %s) = %s" % (da, db, dc, v))
            out.append(self.show('"" + %s + %s + %s' % (da, db, dc)))
            out.append("%s.c += 5" % v)
            out.append(self.show("%s.component3()" % v))
            out.append(self.show("setOf(%s, %s.copy(), %s.copy(a = 1)).size" % (v, v, v)))
        elif k == "sealed":
            out.append(self.show("describe%s(%s)" % (c["name"], self.sealed_obj(c, 1, env))))
            out.append(self.show("listOf(%s, %s).map { describe%s(it) }" % (self.sealed_obj(c, 1, env), self.sealed_obj(c, 1, env), c["name"])))
        elif k == "enum":
            out.append(self.show("%s.values().map { it.bump(%s) }" % (c["name"], ex("Int", 1))))
            out.append(self.show("%s.valueOf(\"%s\")" % (c["name"], self.pick(c["ks"]))))
            out.append(self.show("when (%s.%s) { %s -> 1; else -> 2 }" % (c["name"], self.pick(c["ks"]), "%s.%s" % (c["name"], self.pick(c["ks"])))))
        elif k == "generic":
            g = c["name"]
            out.append(self.show("%s(%s).map { it.toString() + \"!\" }" % (g, ex("Int", 1))))
            out.append(self.show("best%s(%s, %s, %s)" % (g, ex("Int", 1), ex("Int", 1), ex("Int", 1))))
            out.append(self.show("best%s(%s, %s, %s)" % (g, ex("String", 1), ex("String", 1), ex("String", 1))))
            out.append(self.show("%s(listOf(%s)).v.size" % (g, ex("Int", 1))))
        elif k == "object":
            o = c["name"]
            out.append(self.show("%s.next(%s and 15)" % (o, ex("Int", 1))))
            out.append(self.show("%s.log" % o))
        elif k == "graph":
            nn = c["name"]
            g = self.fresh("g")
            out.append("val %s = ring%s(%d, %s)" % (g, nn, r.randint(1, 6), ex("Int", 1)))
            out.append(self.show("%s.sum(%d)" % (g, r.randint(0, 3))))
            out.append(self.show("%s.next!!.next!!.v + %s.back!!.v" % (g, g)))
            if self.chance(0.6):
                out.append("%s.kids.add(%s(%s))" % (g, nn, ex("Int", 1)))
                out.append("%s.kids[0].next = %s" % (g, g))
                out.append(self.show("%s.kids.size + %s.sum(2)" % (g, g)))
            if self.chance(0.5):
                out.append("%s.next = null; %s.back = null" % (g, g))
                out.append(self.show("%s.next?.v ?: -1" % g))
        elif k == "vec":
            vc = c["name"]
            a, b = self.fresh("va"), self.fresh("vb")
            out.append("val %s = %s(%s, %s)" % (a, vc, ex("Int", 1), ex("Int", 1)))
            out.append("val %s = %s.of(%s)" % (b, vc, ex("Int", 1)))
            out.append(self.show("%s + %s * 3" % (a, b)))
            out.append(self.show("-%s" % a))
            out.append(self.show("%s[0] + %s[1] + %s(%s)" % (a, b, a, ex("Int", 1))))
            out.append(self.show("%s < %s" % (a, b)))
            out.append(self.show("listOf(%s, %s, %s.ZERO).sorted()" % (a, b, vc)))
            out.append(self.show("listOf(%s, %s).maxOrNull()" % (a, b)))
            out.append(self.show("listOf(%s, %s).sortedByDescending { it.y }" % (a, b)))
        elif k == "deleg":
            wn = c["name"]
            iface = c["iface"]
            w = self.fresh("w")
            out.append("val %s = %s(%s(%s), %s)" % (w, wn, self.pick(iface["impls"]), ex("Int", 1), ex("Int", 1)))
            out.append(self.show("%s.score(%s)" % (w, ex("Int", 1))))
            out.append(self.show("%s.tag()" % w))
            out.append(self.show("%s.lazyVal + %s.lazyVal" % (w, w)))
            out.append("%s.watched = %s" % (w, ex("Int", 1)))
            out.append(self.show("%s.watched" % w))
            out.append(self.show("%s.In(%s).both(%s)" % (w, ex("Int", 1), ex("Int", 1))))
        elif k == "exc":
            ex_ = c["name"]
            out.append("for (n in 0 until %d) {" % r.randint(1, 5))
            out.append("    try {")
            out.append("        println(thrower%s(n))" % ex_)
            out.append("    } catch (e: %sSub) {" % ex_)
            out.append('        println("sub " + e.message + " " + e.code + " " + e.cause?.message)')
            out.append("    } catch (e: %s) {" % ex_)
            out.append('        println("base " + e.message + " " + e.code)')
            out.append("    }")
            out.append("}")
            out.append("val r%s = try { thrower%s(%s and 7) } catch (e: Exception) { -1 } finally { println(\"outer\") }" % (self.fresh(""), ex_, ex("Int", 1)))
        elif k == "misc":
            m = c["name"]
            out.append(self.show("%sVar(%s, %s, scale = %s)" % (m, ex("Int", 1), ex("Int", 1), ex("Int", 1))))
            out.append(self.show("%sVar(bias = %s)" % (m, ex("Int", 1))))
            out.append(self.show("%sTwice { %s }" % (m, ex("Int", 1))))
            out.append(self.show("%sGcd(%s and 1023, %s and 1023)" % (m, ex("Int", 1), ex("Int", 1))))
            out.append(self.show("%sVar(*intArrayOf(1, 2, %s))" % (m, ex("Int", 1))))
            lv = self.fresh("lf")
            out.append("fun %s(a: Int): Int = a * %s + %s" % (lv, self.lit("Int"), ex("Int", 1)))
            out.append(self.show("(1..3).map(::%s)" % lv))
            out.append("outer@ for (a in 0 until 4) { for (b in 0 until 4) { if (a * b == %d) continue@outer; if (a + b > %d) break@outer; println(a * 10 + b) } }" % (r.randint(0, 6), r.randint(2, 6)))
            out.append("var dw = 0; do { dw += %d } while (dw < %d)" % (r.randint(1, 3), r.randint(3, 12)))
            out.append(self.show("dw"))
            out.append(self.show("when { %s -> \"p\"; %s -> \"q\"; else -> \"r\" }" % (ex("Boolean", 2), ex("Boolean", 2))))
            out.append(self.show("arrayOf(\"b\", \"a\", %s).sortedArray().joinToString()" % ex("String", 1)))
            out.append(self.show("Array(%d) { i -> IntArray(i + 1) { j -> i * j } }.map { it.sum() }" % r.randint(1, 4)))
            out.append(self.show("buildString { append(%s); append('-'); append(%s) }" % (ex("Int", 1), ex("String", 1))))
            out.append(self.show("Triple(%s, %s, %s).toList()" % (ex("Int", 1), ex("String", 1), ex("Boolean", 1))))
        elif k == "ext":
            out.append(self.show("%s.%sA(%s)" % (ex("Int", 1), c["name"], ex("Int", 1))))
            out.append(self.show("%s.%sB(%s)" % (ex("String", 1), c["name"], ex("Int", 1))))
            out.append(self.show("%s.%sC()" % (ex("List<Int>", 2), c["name"])))

    # ------------------------------------------------------------------ whole program
    def program(self, for_jvm=False):
        """-> list of units (each a list of lines); unit 0 is the prelude, the rest are declarations and run() statements."""
        decl_parts = self.make_decls()
        body_units = []
        env = Env()
        self.env = env
        for _ in range(self.size):
            u = []
            self.stmt(u, env, 2, False)
            body_units.append(u)
        return decl_parts, body_units

    def render(self, decl_parts, body_units, package=None):
        out = []
        if package:
            out.append("package %s" % package)
        out.append("import kotlin.coroutines.*")
        out.append("")
        if self.uses_co:
            out.extend(CO_PRELUDE)
        out.append("// --- generated ---")
        for p in decl_parts:
            out.extend(p)
            out.append("")
        out.append("fun run() {")
        for u in body_units:
            out.extend("    " + l for l in u)
        out.append("}")
        out.append("")
        out.append("// --- main ---")
        if package is None:
            out.append("fun main() {")
            out.append("    try {")
            out.append("        run()")
            out.append("    } catch (e: Throwable) {")
            out.append('        println("uncaught " + (e is ArithmeticException) + (e is IndexOutOfBoundsException) + (e is IllegalStateException))')
            out.append("    }")
            out.append("}")
        return "\n".join(out) + "\n"


class _ExtEnv(Env):
    """An environment in which `this` stands for an Int (extension function bodies)."""

    def __init__(self, inner):
        super().__init__(inner)
        self.add("this", "Int")


CO_PRELUDE = """val coParked = ArrayList<Continuation<Int>>()
var coCounter = 0

suspend fun coStep(x: Int): Int = suspendCoroutine { c -> coParked.add(c); coCounter += x and 3 }

fun coResumeAll() {
    while (coParked.isNotEmpty()) {
        val c = coParked.removeAt(0)
        coCounter++
        c.resume(coCounter)
    }
}

fun coLaunch(name: String, block: suspend () -> Any?) {
    block.startCoroutine(object : Continuation<Any?> {
        override val context: CoroutineContext get() = EmptyCoroutineContext
        override fun resumeWith(result: Result<Any?>) {
            println(name + " -> " + result.getOrNull() + (if (result.isFailure) " failed" else ""))
        }
    })
}
""".split("\n")


def generate(seed, size=14, package=None):
    g = Gen(seed, size)
    decls, units = g.program()
    return g.render(decls, units, package), (g, decls, units)


if __name__ == "__main__":
    args = sys.argv[1:]
    size = 14
    if "--size" in args:
        i = args.index("--size")
        size = int(args[i + 1])
        del args[i : i + 2]
    sys.stdout.write(generate(int(args[0]), size)[0])
