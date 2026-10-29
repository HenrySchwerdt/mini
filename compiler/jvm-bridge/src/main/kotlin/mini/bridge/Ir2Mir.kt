@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.BinaryOp
import mini.backend.Mangling
import mini.backend.MirExpr
import mini.backend.MirFunction
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.TBoolean
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNothing
import mini.backend.TString
import mini.backend.TUnit
import mini.backend.UnaryOp
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.statements

/** A Kotlin construct the P0 backend does not implement. The message includes the source location. */
class UnsupportedConstruct(message: String) : Exception(message)

/**
 * Kotlin IR (post fir2ir) to MIR. Everything that touches Kotlin compiler internals
 * lives here; the produced MIR is pure data from the `backend` module.
 */
class Ir2Mir {
    private var labelCounter = 0
    private lateinit var fileEntry: IrFileEntry
    private lateinit var fileName: String

    /** top-level functions of the module being converted: IR function -> C name */
    private val functionNames = HashMap<IrSimpleFunction, String>()

    // per function state
    private var locals = HashMap<Any, MirLocal>()
    private var localCounter = 0
    private val loopLabels = HashMap<IrLoop, MirLabel>()
    private var currentFunction: IrSimpleFunction? = null

    fun convert(module: IrModuleFragment, sourceName: String): MirModule {
        val functions = ArrayList<MirFunction>()
        val pending = ArrayList<Triple<IrFile, IrSimpleFunction, String>>()
        // pass 1: names and unsupported-declaration checks
        for (file in module.files) {
            setFile(file)
            val pkg = file.packageFqName.asString()
            for (d in file.declarations) {
                when (d) {
                    is IrSimpleFunction -> {
                        checkFunctionShape(d)
                        val params = d.parameters.map { mirType(it.type, it) }
                        val cName = Mangling.function(pkg, d.name.asString(), params)
                        if (d.name.asString() == "main" && params.isNotEmpty()) {
                            unsupported(d, "main function with parameters (only `fun main()` is supported)")
                        }
                        functionNames[d] = cName
                        pending += Triple(file, d, cName)
                    }
                    is IrClass -> unsupported(d, "${classKind(d)} declaration '${d.name.asString()}'")
                    is IrProperty -> unsupported(d, "top-level property '${d.name.asString()}'")
                    else -> unsupported(d, describe(d))
                }
            }
        }
        for ((file, f, cName) in pending) {
            setFile(file)
            functions += convertFunction(f, cName, file.packageFqName.asString())
        }
        return MirModule(sourceName, functions)
    }

    private fun setFile(f: IrFile) {
        fileEntry = f.fileEntry
        fileName = f.fileEntry.name
    }

    private fun classKind(c: IrClass): String = when (c.kind.name) {
        "INTERFACE" -> "interface"
        "ENUM_CLASS" -> "enum class"
        "ENUM_ENTRY" -> "enum entry"
        "ANNOTATION_CLASS" -> "annotation class"
        "OBJECT" -> "object"
        else -> "class"
    }

    private fun checkFunctionShape(f: IrSimpleFunction) {
        if (f.typeParameters.isNotEmpty()) unsupported(f, "generic function '${f.name}'")
        if (f.isSuspend) unsupported(f, "suspend function '${f.name}'")
        for (p in f.parameters) {
            when (p.kind) {
                IrParameterKind.Regular -> {}
                IrParameterKind.ExtensionReceiver -> unsupported(f, "extension function '${f.name}'")
                IrParameterKind.Context -> unsupported(f, "context parameter in '${f.name}'")
                IrParameterKind.DispatchReceiver -> unsupported(f, "member function '${f.name}'")
            }
            if (p.defaultValue != null) unsupported(p, "default argument value of parameter '${p.name}'")
            if (p.varargElementType != null) unsupported(p, "vararg parameter '${p.name}'")
        }
        if (f.isExternal) unsupported(f, "external function '${f.name}'")
        if (f.body == null) unsupported(f, "function without body '${f.name}'")
    }

    // ---- locations / errors -----------------------------------------------------------

    private fun location(e: IrElement): String {
        val off = e.startOffset
        if (off < 0) return fileName
        return "$fileName:${fileEntry.getLineNumber(off) + 1}:${fileEntry.getColumnNumber(off) + 1}"
    }

    private fun unsupported(e: IrElement, what: String): Nothing =
        throw UnsupportedConstruct("${location(e)}: error: unsupported construct: $what")

    private fun describe(e: IrElement): String {
        val n = e.javaClass.simpleName.removePrefix("Ir").removeSuffix("Impl")
        return n.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
    }

    // ---- types ------------------------------------------------------------------------

    private fun mirType(t: IrType, at: IrElement): MirType {
        if (t.isMarkedNullable()) unsupported(at, "nullable type '${t.classFqName?.asString() ?: "?"}?'")
        return when (val n = t.classFqName?.asString()) {
            "kotlin.Int" -> TInt
            "kotlin.Long" -> TLong
            "kotlin.Boolean" -> TBoolean
            "kotlin.Double" -> TDouble
            "kotlin.Char" -> TChar
            "kotlin.String" -> TString
            "kotlin.Unit" -> TUnit
            "kotlin.Nothing" -> TNothing
            else -> unsupported(at, "type '${n ?: t}'")
        }
    }

    // ---- functions --------------------------------------------------------------------

    private fun convertFunction(f: IrSimpleFunction, cName: String, pkg: String): MirFunction {
        locals = HashMap()
        localCounter = 0
        currentFunction = f
        val params = f.parameters.map { p ->
            val l = MirLocal(localCounter++, p.name.asString(), mirType(p.type, p))
            locals[p] = l
            l
        }
        val ret = mirType(f.returnType, f)
        val out = ArrayList<MirStmt>()
        when (val body = f.body) {
            is org.jetbrains.kotlin.ir.expressions.IrBlockBody -> for (s in body.statements) stmt(s, out)
            is org.jetbrains.kotlin.ir.expressions.IrExpressionBody -> {
                val e = body.expression
                if (ret == TUnit) stmt(e, out) else out += MirStmt.Return(value(e, out))
            }
            else -> unsupported(f, "function body")
        }
        val qn = (if (pkg.isEmpty()) "" else "$pkg.") + f.name.asString()
        return MirFunction(qn, cName, params, ret, out)
    }

    private fun newTemp(type: MirType, name: String = "tmp"): MirLocal = MirLocal(localCounter++, name, type)

    // ---- statements -------------------------------------------------------------------

    private fun stmt(s: IrStatement, out: MutableList<MirStmt>) {
        when (s) {
            is IrVariable -> {
                if (s.isLateinit) unsupported(s, "lateinit variable '${s.name}'")
                val init = s.initializer?.let { value(it, out) }
                val t = mirType(s.type, s)
                if (t == TUnit || t == TNothing) unsupported(s, "variable '${s.name}' of type $t")
                val l = MirLocal(localCounter++, s.name.asString(), t)
                locals[s] = l
                out += MirStmt.Declare(l, init)
            }
            is IrComposite -> for (x in s.statements) stmt(x, out)
            is IrBlock -> {
                if (s.origin?.debugName == "FOR_LOOP") forLoop(s, out) else for (x in s.statements) stmt(x, out)
            }
            is IrWhen -> whenStmt(s, out)
            is IrWhileLoop -> whileLoop(s, out)
            is IrDoWhileLoop -> doWhileLoop(s, out)
            is IrBreak -> out += MirStmt.Break(labelOf(s.loop, s))
            is IrContinue -> out += MirStmt.Continue(labelOf(s.loop, s))
            is IrReturn -> returnStmt(s, out)
            is IrSetValue -> {
                val l = localOf(s.symbol.owner, s)
                out += MirStmt.Assign(l, convertTo(value(s.value, out), l.type, s))
            }
            is IrTypeOperatorCall -> {
                if (s.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) stmt(s.argument, out)
                else unsupported(s, "type operator ${s.operator}")
            }
            is IrGetObjectValue -> {
                if (s.symbol.owner.kotlinFqName.asString() != "kotlin.Unit") unsupported(s, "object reference '${s.symbol.owner.kotlinFqName}'")
            }
            is IrCall -> if (isNoWhenBranch(s)) out += MirStmt.Panic("NoWhenBranchMatchedException") else out += MirStmt.Eval(call(s, out))
            is IrExpression -> out += MirStmt.Eval(value(s, out))
            else -> unsupported(s, describe(s))
        }
    }

    /** The synthetic `else` branch of an exhaustive `when` without `else`. */
    private fun isNoWhenBranch(c: IrCall): Boolean {
        val o = c.symbol.owner
        return o.name.asString() == "noWhenBranchMatchedException" &&
            (o.parent as? IrPackageFragment)?.packageFqName?.asString() == "kotlin.internal.ir"
    }

    private fun returnStmt(s: IrReturn, out: MutableList<MirStmt>) {
        val f = currentFunction!!
        if (s.returnTargetSymbol.owner !== f) unsupported(s, "return from a lambda or nested function")
        if (mirType(f.returnType, f) == TUnit) {
            stmt(s.value, out)
            out += MirStmt.Return(null)
        } else {
            val v = value(s.value, out)
            if (v.type == TNothing) return
            out += MirStmt.Return(v)
        }
    }

    private fun labelOf(loop: IrLoop, at: IrElement): MirLabel =
        loopLabels[loop] ?: unsupported(at, "jump to a loop outside the current function")

    private fun localOf(owner: Any, at: IrElement): MirLocal =
        locals[owner] ?: unsupported(at, "reference to a variable that is not a local of the current function")

    // ---- loops ------------------------------------------------------------------------

    private fun flattenBody(e: IrExpression?, out: MutableList<MirStmt>) {
        if (e != null) stmt(e, out)
    }

    private fun whileLoop(s: IrWhileLoop, out: MutableList<MirStmt>) {
        val label = MirLabel(labelCounter++)
        loopLabels[s] = label
        val pre = ArrayList<MirStmt>()
        val cond = value(s.condition, pre)
        val body = ArrayList<MirStmt>()
        if (pre.isEmpty()) {
            flattenBody(s.body, body)
            out += MirStmt.While(label, cond, body)
        } else {
            body += pre
            body += MirStmt.If(MirExpr.Unary(UnaryOp.Not, cond), listOf(MirStmt.Break(label)), emptyList())
            flattenBody(s.body, body)
            out += MirStmt.Loop(label, body)
        }
    }

    private fun doWhileLoop(s: IrDoWhileLoop, out: MutableList<MirStmt>) {
        val label = MirLabel(labelCounter++)
        loopLabels[s] = label
        val body = ArrayList<MirStmt>()
        flattenBody(s.body, body)
        val pre = ArrayList<MirStmt>()
        val cond = value(s.condition, pre)
        out += MirStmt.DoWhile(label, body, pre, cond)
    }

    private class Progression(
        val start: IrExpression,
        val end: IrExpression,
        val step: IrExpression?,
        val ascending: Boolean,
        val inclusive: Boolean,
    )

    private fun progression(e: IrExpression): Progression {
        if (e is IrCall) {
            val owner = e.symbol.owner
            val parent = owner.parent
            val name = owner.name.asString()
            val args = e.arguments
            if (parent is IrPackageFragment && parent.packageFqName.asString() == "kotlin.ranges") {
                when (name) {
                    "downTo" -> return Progression(args[0]!!, args[1]!!, null, ascending = false, inclusive = true)
                    "until" -> return Progression(args[0]!!, args[1]!!, null, ascending = true, inclusive = false)
                    "step" -> {
                        val inner = progression(args[0]!!)
                        if (inner.step != null) unsupported(e, "step applied to a progression that already has a step")
                        return Progression(inner.start, inner.end, args[1]!!, inner.ascending, inner.inclusive)
                    }
                }
            }
            if (parent is IrClass && name == "rangeTo") {
                val fq = parent.kotlinFqName.asString()
                if (fq == "kotlin.Int" || fq == "kotlin.Long" || fq == "kotlin.Char") {
                    return Progression(args[0]!!, args[1]!!, null, ascending = true, inclusive = true)
                }
            }
        }
        unsupported(e, "for loop over something other than an Int/Long/Char range built with `..`, `until`, `downTo` or `step`")
    }

    /** `for (x in range)`: fir2ir leaves the iterator protocol in place; match it. */
    private fun forLoop(b: IrBlock, out: MutableList<MirStmt>) {
        val st = b.statements
        val iter = st.getOrNull(0) as? IrVariable
        val loop = st.getOrNull(1) as? IrWhileLoop
        val iterCall = iter?.initializer as? IrCall
        if (iter == null || loop == null || iterCall == null || st.size != 2) unsupported(b, "for loop shape")
        val elemType = when (iter.type.classFqName?.asString()) {
            "kotlin.collections.IntIterator" -> TInt
            "kotlin.collections.LongIterator" -> TLong
            "kotlin.collections.CharIterator" -> TChar
            else -> unsupported(b, "for loop over '${iter.type.classFqName}'")
        }
        val p = progression(iterCall.arguments[0]!!)

        val body = loop.body as? IrBlock ?: unsupported(loop, "for loop body")
        val loopVar = body.statements.getOrNull(0) as? IrVariable ?: unsupported(loop, "for loop variable")
        val label = MirLabel(labelCounter++)
        loopLabels[loop] = label

        val exprs = listOfNotNull(p.start, p.end, p.step)
        val vals = operands(exprs, out)
        val start = convertTo(vals[0], elemType, b)
        val end = convertTo(vals[1], elemType, b)
        val step = vals.getOrNull(2)
        val mirVar = MirLocal(localCounter++, loopVar.name.asString(), elemType)
        locals[loopVar] = mirVar
        val inner = ArrayList<MirStmt>()
        for (x in body.statements.drop(1)) stmt(x, inner)
        out += MirStmt.ForRange(label, mirVar, start, end, step, p.ascending, p.inclusive, inner)
    }

    // ---- when / if --------------------------------------------------------------------

    private fun isConstTrue(e: IrExpression) = e is IrConst && e.value == true

    private fun whenStmt(w: IrWhen, out: MutableList<MirStmt>) = whenInto(w, out, null)

    /** Lowers [w] as an if-chain. If [target] is not null every branch assigns its value to it. */
    private fun whenInto(w: IrWhen, out: MutableList<MirStmt>, target: MirLocal?) {
        class Piece(val condStmts: List<MirStmt>, val cond: MirExpr?, val body: List<MirStmt>)
        val pieces = w.branches.map { br ->
            val cs = ArrayList<MirStmt>()
            val c = if (isConstTrue(br.condition)) null else value(br.condition, cs)
            val body = ArrayList<MirStmt>()
            if (target != null && br.result.type.classFqName?.asString() != "kotlin.Nothing") {
                val v = value(br.result, body)
                body += MirStmt.Assign(target, convertTo(v, target.type, br.result))
            } else {
                stmt(br.result, body)
            }
            Piece(cs, c, body)
        }
        fun build(i: Int): List<MirStmt> {
            if (i >= pieces.size) return emptyList()
            val p = pieces[i]
            val c = p.cond ?: return p.body
            return p.condStmts + MirStmt.If(c, p.body, build(i + 1))
        }
        out += build(0)
    }

    private fun whenValue(w: IrWhen, out: MutableList<MirStmt>): MirExpr {
        val type = mirType(w.type, w)
        if (type == TUnit || type == TNothing) {
            whenInto(w, out, null)
            return if (type == TUnit) MirExpr.UnitValue else MirExpr.Unreachable(TNothing)
        }
        // Short-circuit operators and pure if-else expressions become expressions.
        val br = w.branches
        val origin = w.origin?.debugName
        if (br.size == 2 && isConstTrue(br[1].condition)) {
            val c = ArrayList<MirStmt>(); val a = ArrayList<MirStmt>(); val b = ArrayList<MirStmt>()
            val cv = value(br[0].condition, c)
            val av = value(br[0].result, a)
            val bv = value(br[1].result, b)
            if (c.isEmpty() && a.isEmpty() && b.isEmpty() && av.type != TNothing && bv.type != TNothing) {
                if (origin == "ANDAND") return MirExpr.Binary(BinaryOp.AndAnd, cv, av)
                if (origin == "OROR") return MirExpr.Binary(BinaryOp.OrOr, cv, bv)
                return MirExpr.Conditional(cv, av, bv)
            }
            // General form, reusing the pieces lowered above (no second lowering).
            val tmp = newTemp(type)
            out += MirStmt.Declare(tmp, null)
            out += c
            fun assign(vv: MirExpr, body: MutableList<MirStmt>, r: IrExpression) {
                if (vv.type != TNothing) body += MirStmt.Assign(tmp, convertTo(vv, type, r))
            }
            assign(av, a, br[0].result)
            assign(bv, b, br[1].result)
            out += MirStmt.If(cv, a, b)
            return MirExpr.Local(tmp)
        }
        val tmp = newTemp(type)
        out += MirStmt.Declare(tmp, null)
        whenInto(w, out, tmp)
        return MirExpr.Local(tmp)
    }

    // ---- expressions ------------------------------------------------------------------

    /**
     * Lowers [args] left to right. If a later operand needs hoisted statements, the
     * values of earlier operands are first stored in temporaries so evaluation order is kept.
     */
    private fun operands(args: List<IrExpression>, out: MutableList<MirStmt>): List<MirExpr> {
        val results = ArrayList<MirExpr>()
        for (a in args) {
            val before = out.size
            val v = value(a, out)
            if (out.size > before) {
                var insertAt = before
                for (j in results.indices) {
                    val r = results[j]
                    if (r is MirExpr.IntConst || r is MirExpr.LongConst || r is MirExpr.DoubleConst ||
                        r is MirExpr.BooleanConst || r is MirExpr.CharConst || r is MirExpr.StringConst
                    ) continue
                    val t = newTemp(r.type)
                    out.add(insertAt++, MirStmt.Declare(t, r))
                    results[j] = MirExpr.Local(t)
                }
            }
            results += v
        }
        return results
    }

    private fun value(e: IrExpression, out: MutableList<MirStmt>): MirExpr = when (e) {
        is IrConst -> constant(e)
        is IrGetValue -> {
            val owner = e.symbol.owner
            if (owner is IrValueParameter && owner.kind != IrParameterKind.Regular) unsupported(e, "receiver parameter")
            MirExpr.Local(localOf(owner, e))
        }
        is IrCall -> if (isNoWhenBranch(e)) {
            out += MirStmt.Panic("NoWhenBranchMatchedException")
            MirExpr.Unreachable(TNothing)
        } else call(e, out)
        is IrStringConcatenation -> stringConcat(e, out)
        is IrWhen -> whenValue(e, out)
        is IrComposite -> blockValue(e.statements, e, out)
        is IrBlock -> {
            if (e.origin?.debugName == "FOR_LOOP") { forLoop(e, out); MirExpr.UnitValue }
            else blockValue(e.statements, e, out)
        }
        is IrTypeOperatorCall -> {
            if (e.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) { stmt(e.argument, out); MirExpr.UnitValue }
            else unsupported(e, "type operator ${e.operator}")
        }
        is IrGetObjectValue -> {
            stmt(e, out); MirExpr.UnitValue
        }
        is IrSetValue, is IrWhileLoop, is IrDoWhileLoop -> { stmt(e, out); MirExpr.UnitValue }
        is IrReturn, is IrBreak, is IrContinue -> { stmt(e, out); MirExpr.Unreachable(TNothing) }
        else -> unsupported(e, describe(e))
    }

    private fun blockValue(statements: List<IrStatement>, at: IrExpression, out: MutableList<MirStmt>): MirExpr {
        if (statements.isEmpty()) return MirExpr.UnitValue
        for (s in statements.dropLast(1)) stmt(s, out)
        val last = statements.last()
        return if (last is IrExpression) value(last, out) else { stmt(last, out); MirExpr.UnitValue }
    }

    private fun constant(c: IrConst): MirExpr = when (val v = c.value) {
        is Int -> MirExpr.IntConst(v)
        is Long -> MirExpr.LongConst(v)
        is Double -> MirExpr.DoubleConst(v)
        is Boolean -> MirExpr.BooleanConst(v)
        is Char -> MirExpr.CharConst(v.code)
        is String -> MirExpr.StringConst(v)
        null -> unsupported(c, "null literal")
        else -> unsupported(c, "constant of type ${v::class.simpleName}")
    }

    private fun convertTo(e: MirExpr, to: MirType, at: IrElement): MirExpr {
        if (e.type == to || e.type == TNothing) return e
        val from = e.type
        val ok = (from == TInt || from == TLong || from == TChar || from == TDouble) &&
            (to == TInt || to == TLong || to == TChar || to == TDouble)
        if (!ok) unsupported(at, "conversion from $from to $to")
        return MirExpr.Convert(e, to)
    }

    private fun toStringExpr(e: MirExpr, at: IrElement): MirExpr = when (e.type) {
        TString -> e
        TInt -> MirExpr.RuntimeCall("mk_string_from_int", listOf(e), TString)
        TLong -> MirExpr.RuntimeCall("mk_string_from_long", listOf(e), TString)
        TDouble -> MirExpr.RuntimeCall("mk_string_from_double", listOf(e), TString)
        TBoolean -> MirExpr.RuntimeCall("mk_string_from_boolean", listOf(e), TString)
        TChar -> MirExpr.RuntimeCall("mk_string_from_char", listOf(e), TString)
        else -> unsupported(at, "string conversion of a value of type ${e.type}")
    }

    private fun concat(a: MirExpr, b: MirExpr): MirExpr {
        if (a is MirExpr.StringConst && b is MirExpr.StringConst) return MirExpr.StringConst(a.value + b.value)
        return MirExpr.RuntimeCall("mk_string_concat", listOf(a, b), TString)
    }

    private fun stringConcat(e: IrStringConcatenation, out: MutableList<MirStmt>): MirExpr {
        val vals = operands(e.arguments, out)
        var acc: MirExpr? = null
        for (v in vals) {
            val s = toStringExpr(v, e)
            acc = if (acc == null) s else concat(acc, s)
        }
        return acc ?: MirExpr.StringConst("")
    }

    // ---- calls ------------------------------------------------------------------------

    private fun call(c: IrCall, out: MutableList<MirStmt>): MirExpr {
        val owner = c.symbol.owner
        val name = owner.name.asString()
        val parent = owner.parent

        // user function of this module
        functionNames[owner]?.let { cName ->
            val args = c.arguments.mapIndexed { i, a -> a ?: unsupported(c, "default argument for parameter ${i + 1} of '$name'") }
            val vals = operands(args, out)
            return MirExpr.Call(cName, vals, mirType(c.type, c))
        }

        val ownerName = when (parent) {
            is IrClass -> parent.kotlinFqName.asString()
            is IrPackageFragment -> parent.packageFqName.asString()
            else -> unsupported(c, "call of '$name'")
        }
        val args = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$ownerName.$name'") }

        return when {
            ownerName == "kotlin" && name == "<get-code>" && args.size == 1 ->
                convertTo(operands(args, out)[0], TInt, c)
            ownerName == "kotlin.io" && (name == "println" || name == "print") -> consoleCall(c, name, args, out)
            ownerName == "kotlin.internal.ir" -> irBuiltin(c, name, args, out)
            parent is IrClass && ownerName in RANGES && name == "contains" -> rangeContains(c, args, out)
            ownerName == "kotlin.String" && name == "<get-length>" -> MirExpr.StringLength(operands(args, out)[0])
            parent is IrClass && ownerName in PRIMITIVES -> primitiveMember(c, ownerName, name, args, out)
            else -> unsupported(c, "call to '${if (parent is IrClass) "$ownerName.$name" else "$ownerName.$name"}'")
        }
    }

    /** `x in a..b` / `x in a until b` (the range is built in place; no range object exists). */
    private fun rangeContains(c: IrCall, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
        if (args.size != 2) unsupported(c, "range contains")
        val p = progression(args[0])
        if (p.step != null || !p.ascending) unsupported(c, "`in` on a progression with step or direction")
        val vals = operands(listOf(p.start, p.end, args[1]), out)
        val t = widest(widest(vals[0].type, vals[1].type, c), vals[2].type, c)
        val parts = vals.map { v ->
            val x = convertTo(widenCharIf(v, t), t, c)
            if (x is MirExpr.IntConst || x is MirExpr.LongConst || x is MirExpr.CharConst || x is MirExpr.Local) x
            else {
                val tmp = newTemp(x.type)
                out += MirStmt.Declare(tmp, x)
                MirExpr.Local(tmp)
            }
        }
        val lo = MirExpr.Binary(BinaryOp.Ge, parts[2], parts[0])
        val hi = MirExpr.Binary(if (p.inclusive) BinaryOp.Le else BinaryOp.Lt, parts[2], parts[1])
        return MirExpr.Binary(BinaryOp.AndAnd, lo, hi)
    }

    private fun widenCharIf(e: MirExpr, t: MirType): MirExpr = if (e.type == TChar && t != TChar) MirExpr.Convert(e, TInt) else e

    private fun consoleCall(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
        if (args.isEmpty()) {
            if (name == "println") return MirExpr.RuntimeCall("mk_println_empty", emptyList(), TUnit)
            unsupported(c, "print() without argument")
        }
        if (args.size != 1) unsupported(c, "call to '$name'")
        val v = operands(args, out)[0]
        if (name == "print") return MirExpr.RuntimeCall("mk_print_string", listOf(toStringExpr(v, c)), TUnit)
        return when (v.type) {
            TString -> MirExpr.RuntimeCall("mk_println_string", listOf(v), TUnit)
            TInt -> MirExpr.RuntimeCall("mk_println_int", listOf(v), TUnit)
            TLong -> MirExpr.RuntimeCall("mk_println_long", listOf(v), TUnit)
            TDouble -> MirExpr.RuntimeCall("mk_println_double", listOf(v), TUnit)
            TBoolean -> MirExpr.RuntimeCall("mk_println_boolean", listOf(v), TUnit)
            TChar -> MirExpr.RuntimeCall("mk_println_char", listOf(v), TUnit)
            else -> unsupported(c, "println of a value of type ${v.type}")
        }
    }

    private fun irBuiltin(c: IrCall, name: String, args: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
        val op = when (name) {
            "less" -> BinaryOp.Lt
            "lessOrEqual" -> BinaryOp.Le
            "greater" -> BinaryOp.Gt
            "greaterOrEqual" -> BinaryOp.Ge
            "EQEQ", "ieee754equals" -> BinaryOp.Eq
            else -> unsupported(c, "compiler intrinsic '$name'")
        }
        val v = operands(args, out)
        if (v.size != 2) unsupported(c, "compiler intrinsic '$name'")
        if (v[0].type != v[1].type) unsupported(c, "comparison of ${v[0].type} with ${v[1].type}")
        if (v[0].type == TUnit || v[0].type == TNothing) unsupported(c, "comparison of ${v[0].type}")
        if (v[0].type == TString && op != BinaryOp.Eq) unsupported(c, "ordering comparison of strings")
        return MirExpr.Binary(op, v[0], v[1])
    }

    private fun arithmeticOp(name: String): BinaryOp? = when (name) {
        "plus" -> BinaryOp.Add; "minus" -> BinaryOp.Sub; "times" -> BinaryOp.Mul
        "div" -> BinaryOp.Div; "rem" -> BinaryOp.Rem
        "and" -> BinaryOp.And; "or" -> BinaryOp.Or; "xor" -> BinaryOp.Xor
        "shl" -> BinaryOp.Shl; "shr" -> BinaryOp.Shr; "ushr" -> BinaryOp.Ushr
        else -> null
    }

    private fun primitiveMember(
        c: IrCall,
        cls: String,
        name: String,
        args: List<IrExpression>,
        out: MutableList<MirStmt>,
    ): MirExpr {
        val v = operands(args, out)
        val recv = v[0]
        val resultType = mirType(c.type, c)

        // String members
        if (cls == "kotlin.String") {
            return when (name) {
                "plus" -> concat(recv, toStringExpr(v[1], c))
                "toString" -> recv
                else -> unsupported(c, "call to 'kotlin.String.$name'")
            }
        }

        // conversions and toString
        when (name) {
            "toString" -> return toStringExpr(recv, c)
            "toInt" -> return convertTo(recv, TInt, c)
            "toLong" -> return convertTo(recv, TLong, c)
            "toDouble" -> return convertTo(recv, TDouble, c)
            "toChar" -> return convertTo(recv, TChar, c)
            "<get-code>" -> return convertTo(recv, TInt, c)
        }

        if (cls == "kotlin.Boolean") {
            return when (name) {
                "not" -> MirExpr.Unary(UnaryOp.Not, recv)
                "and" -> MirExpr.Binary(BinaryOp.And, recv, v[1])
                "or" -> MirExpr.Binary(BinaryOp.Or, recv, v[1])
                "xor" -> MirExpr.Binary(BinaryOp.Xor, recv, v[1])
                else -> unsupported(c, "call to 'kotlin.Boolean.$name'")
            }
        }

        // unary numeric
        if (v.size == 1) {
            val numeric = if (recv.type == TChar) MirExpr.Convert(recv, TInt) else recv
            return when (name) {
                "unaryMinus" -> MirExpr.Unary(UnaryOp.Neg, numeric)
                "unaryPlus" -> numeric
                "inv" -> MirExpr.Unary(UnaryOp.Inv, recv)
                "inc" -> convertTo(incDec(numeric, BinaryOp.Add), recv.type, c)
                "dec" -> convertTo(incDec(numeric, BinaryOp.Sub), recv.type, c)
                else -> unsupported(c, "call to '$cls.$name'")
            }
        }

        // compareTo
        if (name == "compareTo") {
            val t = widest(recv.type, v[1].type, c)
            val a = convertTo(widenChar(recv), t, c)
            val b = convertTo(widenChar(v[1]), t, c)
            // (a > b) - (a < b) with Int result
            return MirExpr.Binary(
                BinaryOp.Sub,
                convertTo(MirExpr.Conditional(MirExpr.Binary(BinaryOp.Gt, a, b), MirExpr.IntConst(1), MirExpr.IntConst(0)), TInt, c),
                MirExpr.Conditional(MirExpr.Binary(BinaryOp.Lt, a, b), MirExpr.IntConst(1), MirExpr.IntConst(0)),
            )
        }

        // binary arithmetic / bit operations: compute in the wider type
        val op = arithmeticOp(name) ?: unsupported(c, "call to '$cls.$name'")
        if (op.isShift) {
            return MirExpr.Binary(op, recv, v[1])
        }
        if (recv.type == TChar || v[1].type == TChar) {
            // Char + Int -> Char, Char - Int -> Char, Char - Char -> Int
            val t = TInt
            val r = MirExpr.Binary(op, convertTo(recv, t, c), convertTo(v[1], t, c))
            return convertTo(r, resultType, c)
        }
        val t = widest(recv.type, v[1].type, c)
        val r = MirExpr.Binary(op, convertTo(recv, t, c), convertTo(v[1], t, c))
        return convertTo(r, resultType, c)
    }

    private fun widenChar(e: MirExpr): MirExpr = if (e.type == TChar) MirExpr.Convert(e, TInt) else e

    private fun incDec(e: MirExpr, op: BinaryOp): MirExpr = when (e.type) {
        TInt -> MirExpr.Binary(op, e, MirExpr.IntConst(1))
        TLong -> MirExpr.Binary(op, e, MirExpr.LongConst(1))
        TDouble -> MirExpr.Binary(op, e, MirExpr.DoubleConst(1.0))
        else -> throw IllegalStateException("inc/dec on ${e.type}")
    }

    private fun widest(a: MirType, b: MirType, at: IrElement): MirType {
        fun rank(t: MirType) = when (t) {
            TInt, TChar -> 1; TLong -> 2; TDouble -> 3
            else -> unsupported(at, "arithmetic on $t")
        }
        return if (rank(a) >= rank(b)) (if (a == TChar) TInt else a) else b
    }

    private companion object {
        val RANGES = setOf("kotlin.ranges.IntRange", "kotlin.ranges.LongRange", "kotlin.ranges.CharRange")
        val PRIMITIVES = setOf("kotlin.Int", "kotlin.Long", "kotlin.Double", "kotlin.Boolean", "kotlin.Char", "kotlin.String")
    }
}
