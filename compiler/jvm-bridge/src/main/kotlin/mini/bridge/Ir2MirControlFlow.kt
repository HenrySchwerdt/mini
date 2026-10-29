@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.ANY_CNAME
import mini.backend.BinaryOp
import mini.backend.Mangling
import mini.backend.MirClass
import mini.backend.MirExpr
import mini.backend.MirField
import mini.backend.MirFunction
import mini.backend.MirItable
import mini.backend.MirLabel
import mini.backend.MirLocal
import mini.backend.MirModule
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.TBoolean
import mini.backend.TBoxed
import mini.backend.TChar
import mini.backend.TDouble
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNString
import mini.backend.TNothing
import mini.backend.TNull
import mini.backend.TObject
import mini.backend.TString
import mini.backend.TUnit
import mini.backend.UnaryOp
import mini.backend.asNullable
import mini.backend.isOwned
import mini.backend.isPrimitive
import mini.backend.isRef
import mini.backend.nonNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrAnonymousInitializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDoWhileLoop
import org.jetbrains.kotlin.ir.expressions.IrEnumConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrInstanceInitializerCall
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrStringConcatenation
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.statements

/* Ir2Mir: loops, for-ranges, when / if. */

// ---- loops ------------------------------------------------------------------------

internal fun Ir2Mir.flattenBody(e: IrExpression?, out: MutableList<MirStmt>) {
    if (e != null) stmt(e, out)
}

internal fun Ir2Mir.whileLoop(s: IrWhileLoop, out: MutableList<MirStmt>) {
    val label = MirLabel(labelCounter++)
    loopLabels[s] = label
    loopFinallyDepth[s] = finallyStack.size
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

internal fun Ir2Mir.doWhileLoop(s: IrDoWhileLoop, out: MutableList<MirStmt>) {
    val label = MirLabel(labelCounter++)
    loopLabels[s] = label
    loopFinallyDepth[s] = finallyStack.size
    val body = ArrayList<MirStmt>()
    flattenBody(s.body, body)
    val pre = ArrayList<MirStmt>()
    val cond = value(s.condition, pre)
    out += MirStmt.DoWhile(label, body, pre, cond)
}


internal fun Ir2Mir.progression(e: IrExpression): Progression {
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
internal fun Ir2Mir.forLoop(b: IrBlock, out: MutableList<MirStmt>) {
    val st = b.statements
    val iter = st.getOrNull(0) as? IrVariable
    val loop = st.getOrNull(1) as? IrWhileLoop
    val iterCall = iter?.initializer as? IrCall
    if (iter == null || loop == null || iterCall == null || st.size != 2) unsupported(b, "for loop shape")
    if (arrayForLoop(b, loop, iterCall, out)) return
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
    loopFinallyDepth[loop] = finallyStack.size

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

internal fun Ir2Mir.isConstTrue(e: IrExpression) = e is IrConst && e.value == true

internal fun Ir2Mir.whenStmt(w: IrWhen, out: MutableList<MirStmt>) = whenInto(w, out, null)

/** Lowers [w] as an if-chain. If [target] is not null every branch assigns its value to it. */
internal fun Ir2Mir.whenInto(w: IrWhen, out: MutableList<MirStmt>, target: MirLocal?) {
    class Piece(val condStmts: List<MirStmt>, val cond: MirExpr?, val body: List<MirStmt>)
    val pieces = w.branches.map { br ->
        val cs = ArrayList<MirStmt>()
        val c = if (isConstTrue(br.condition)) null else value(br.condition, cs)
        val body = ArrayList<MirStmt>()
        if (target != null && br.result.type.classFqName?.asString() != "kotlin.Nothing") {
            body += MirStmt.Assign(target, bindValue(br.result, target.type, body))
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

internal fun Ir2Mir.whenValue(w: IrWhen, out: MutableList<MirStmt>): MirExpr {
    val type = mirType(w.type, w)
    if (type == TUnit || type == TNothing) {
        whenInto(w, out, null)
        return if (type == TUnit) MirExpr.UnitValue else MirExpr.Unreachable(TNothing)
    }
    // Short-circuit operators and pure if-else expressions over primitives become expressions.
    val br = w.branches
    val origin = w.origin?.debugName
    if (br.size == 2 && isConstTrue(br[1].condition)) {
        val c = ArrayList<MirStmt>(); val a = ArrayList<MirStmt>(); val b = ArrayList<MirStmt>()
        val cv = value(br[0].condition, c)
        val av = value(br[0].result, a)
        val bv = value(br[1].result, b)
        if (c.isEmpty() && a.isEmpty() && b.isEmpty() && av.type != TNothing && bv.type != TNothing && !type.isRef &&
            !av.type.isRef && !bv.type.isRef
        ) {
            if (origin == "ANDAND") return MirExpr.Binary(BinaryOp.AndAnd, cv, av)
            if (origin == "OROR") return MirExpr.Binary(BinaryOp.OrOr, cv, bv)
            return MirExpr.Conditional(cv, av, bv, type)
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
