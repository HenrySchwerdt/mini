@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.kotlinFqName

/*
 * Ir2Mir: arrays (`Array<T>`, IntArray, LongArray, DoubleArray, BooleanArray, CharArray), `vararg`
 * and spread, `for (x in array)`. Arrays are runtime objects (MkArray, see minirt.h v1-L); an
 * `Array<T>` holds references (primitives are boxed: erasure), the typed arrays hold raw values.
 * Element access goes through bounds-checked runtime calls that raise
 * ArrayIndexOutOfBoundsException.
 */

internal class ArrayKind(val cName: String, val qualified: String, val elem: MirType?, val suffix: String)

private val ARRAY_KINDS = listOf(
    ArrayKind("MkIntArray", "kotlin.IntArray", TInt, "int"),
    ArrayKind("MkLongArray", "kotlin.LongArray", TLong, "long"),
    ArrayKind("MkDoubleArray", "kotlin.DoubleArray", TDouble, "double"),
    ArrayKind("MkBooleanArray", "kotlin.BooleanArray", TBoolean, "boolean"),
    ArrayKind("MkCharArray", "kotlin.CharArray", TChar, "char"),
    ArrayKind("MkObjectArray", "kotlin.Array", null, "obj"),
    ArrayKind("MkFloatArray", "kotlin.FloatArray", TFloat, "float"),
)

internal fun arrayKindOfFq(fq: String?): ArrayKind? = when (fq) {
    null -> null
    "kotlin.Array", "kotlin.enums.EnumEntries" -> ARRAY_KINDS[5]
    else -> ARRAY_KINDS.firstOrNull { it.qualified == fq }
}

internal fun arrayKindOf(t: MirType): ArrayKind? = (t as? TObject)?.let { o -> ARRAY_KINDS.firstOrNull { it.cName == o.cName } }

internal fun arrayType(k: ArrayKind, nullable: Boolean = false) = TObject(k.qualified, k.cName, nullable)

private val anyN = TObject("kotlin.Any", ANY_CNAME, true)

internal fun Ir2Mir.arrayGet(k: ArrayKind, arr: MirExpr, idx: MirExpr): MirExpr =
    MirExpr.RuntimeCall("mk_array_get_${k.suffix}", listOf(arr, idx), k.elem ?: anyN)

internal fun Ir2Mir.arraySet(k: ArrayKind, arr: MirExpr, idx: MirExpr, v: MirExpr): MirExpr =
    MirExpr.RuntimeCall("mk_array_set_${k.suffix}", listOf(arr, idx, v), TUnit)

internal fun Ir2Mir.arrayLength(arr: MirExpr): MirExpr = MirExpr.RuntimeCall("mk_array_length", listOf(arr), TInt)

private fun Ir2Mir.local(v: MirExpr, out: MutableList<MirStmt>): MirExpr =
    if (v is MirExpr.Local) v else tmpOf(v, out)

/** Member calls on arrays (and enum `entries`, which share the array representation). */
internal fun Ir2Mir.arrayMember(c: IrCall, name: String, kind: ArrayKind, out: MutableList<MirStmt>): MirExpr {
    val args = c.arguments.map { it ?: unsupported(c, "omitted argument") }
    val v = operands(args, out)
    val expected = mirType(c.type, c)
    return when (name) {
        "get" -> adaptResult(arrayGet(kind, v[0], v[1]), expected, c, out)
        "set" -> arraySet(kind, v[0], v[1], coerceFlexible(v[2], kind.elem ?: anyN, c, out))
        "<get-size>" -> arrayLength(v[0])
        else -> unsupported(c, "call to array member '$name'")
    }
}

internal fun Ir2Mir.arrayConstructor(c: IrConstructorCall, kind: ArrayKind, out: MutableList<MirStmt>): MirExpr {
    val args = c.arguments.map { it ?: unsupported(c, "omitted argument") }
    val size = operands(listOf(args[0]), out)[0]
    val arr = tmpOf(MirExpr.RuntimeCall("mk_array_new_${kind.suffix}", listOf(size), arrayType(kind)), out)
    if (args.size == 2) fillWith(kind, arr, args[1], c, out)
    return arr
}

/** `for (i in indices) arr[i] = init(i)`. */
private fun Ir2Mir.fillWith(kind: ArrayKind, arr: MirExpr, init: IrExpression, at: IrElement, out: MutableList<MirStmt>) {
    val label = MirLabel(labelCounter++)
    val i = MirLocal(localCounter++, "i", TInt)
    val body = ArrayList<MirStmt>()
    val v = callFunctionArg(init, listOf(MirExpr.Local(i)), kind.elem ?: anyN, at, body)
    body += MirStmt.Eval(arraySet(kind, arr, MirExpr.Local(i), coerceFlexible(v, kind.elem ?: anyN, at, body)))
    out += MirStmt.ForRange(label, i, MirExpr.IntConst(0), arrayLength(arr), null, true, false, body)
}

/** `arrayOf`, `intArrayOf`, ..., `arrayOfNulls`, `emptyArray`, and `contentToString`. */
internal fun Ir2Mir.arrayFunction(c: IrCall, name: String, pkg: String, out: MutableList<MirStmt>): MirExpr? {
    val a = c.arguments.map { it ?: return null }
    val expected = mirType(c.type, c)
    if (pkg == "kotlin") {
        val kind = when (name) {
            "arrayOf", "emptyArray", "arrayOfNulls" -> ARRAY_KINDS[5]
            "intArrayOf" -> ARRAY_KINDS[0]
            "longArrayOf" -> ARRAY_KINDS[1]
            "doubleArrayOf" -> ARRAY_KINDS[2]
            "floatArrayOf" -> ARRAY_KINDS[6]
            "booleanArrayOf" -> ARRAY_KINDS[3]
            "charArrayOf" -> ARRAY_KINDS[4]
            else -> null
        }
        if (kind != null) {
            return when (name) {
                "arrayOfNulls" -> MirExpr.RuntimeCall("mk_array_new_obj", listOf(operands(a, out)[0]), arrayType(kind))
                "emptyArray" -> MirExpr.RuntimeCall("mk_array_new_obj", listOf(MirExpr.IntConst(0)), arrayType(kind))
                else -> varargArray(a[0] as? IrVararg ?: return null, kind, out)
            }
        }
    }
    if (pkg == "kotlin.collections" && name == "contentToString" && a.size == 1) {
        val v = operands(a, out)[0]
        if (arrayKindOf(v.type) == null) return null
        return MirExpr.RuntimeCall("mk_array_content_to_string", listOf(v), TString)
    }
    if (pkg == "kotlin.collections" && (name == "<get-indices>" || name == "<get-lastIndex>") && a.size == 1) {
        val v = operands(a, out)[0]
        if (arrayKindOf(v.type) == null) return null
        if (name == "<get-lastIndex>") return MirExpr.Binary(BinaryOp.Sub, arrayLength(v), MirExpr.IntConst(1))
        unsupported(c, "`indices` used as a value (only `for (i in array.indices)` is supported)")
    }
    return null
}

/** The array built for a `vararg` argument or an `arrayOf(...)` call, with spread elements. */
internal fun Ir2Mir.varargArray(e: IrVararg, kind: ArrayKind, out: MutableList<MirStmt>): MirExpr {
    val et = kind.elem ?: anyN
    val hasSpread = e.elements.any { it is IrSpreadElement }
    // evaluate the elements in order
    val vals = ArrayList<Pair<MirExpr, Boolean>>()
    for (el in e.elements) {
        when (el) {
            is IrSpreadElement -> vals += local(operands(listOf(el.expression), out)[0], out) to true
            is IrExpression -> {
                val v = operands(listOf(el), out)[0]
                val cv = coerceFlexible(v, et, e, out)
                vals += (if (cv is MirExpr.Local || cv is MirExpr.IntConst || cv is MirExpr.StringConst || cv is MirExpr.BooleanConst ||
                    cv is MirExpr.LongConst || cv is MirExpr.DoubleConst || cv is MirExpr.CharConst || cv is MirExpr.NullConst) cv
                else if (cv.isOwned()) tmpOf(cv, out) else tmpOfPlain(cv, out)) to false
            }
            else -> unsupported(e, "vararg element")
        }
    }
    var len: MirExpr = MirExpr.IntConst(vals.count { !it.second })
    for ((v, spread) in vals) if (spread) len = MirExpr.Binary(BinaryOp.Add, len, arrayLength(v))
    val arr = tmpOf(MirExpr.RuntimeCall("mk_array_new_${kind.suffix}", listOf(len), arrayType(kind)), out)
    if (!hasSpread) {
        for ((i, p) in vals.withIndex()) out += MirStmt.Eval(arraySet(kind, arr, MirExpr.IntConst(i), p.first))
    } else {
        val pos = MirLocal(localCounter++, "pos", TInt)
        out += MirStmt.Declare(pos, MirExpr.IntConst(0))
        for ((v, spread) in vals) {
            if (spread) {
                out += MirStmt.Eval(MirExpr.RuntimeCall("mk_array_copy_into", listOf(arr, MirExpr.Local(pos), v), TUnit))
                out += MirStmt.Assign(pos, MirExpr.Binary(BinaryOp.Add, MirExpr.Local(pos), arrayLength(v)))
            } else {
                out += MirStmt.Eval(arraySet(kind, arr, MirExpr.Local(pos), v))
                out += MirStmt.Assign(pos, MirExpr.Binary(BinaryOp.Add, MirExpr.Local(pos), MirExpr.IntConst(1)))
            }
        }
    }
    return arr
}

internal fun Ir2Mir.varargValue(e: IrVararg, out: MutableList<MirStmt>): MirExpr {
    val t = mirType(e.type, e)
    val kind = arrayKindOf(t) ?: unsupported(e, "vararg of type $t")
    return varargArray(e, kind, out)
}

/**
 * `for (x in array)` / `for (i in array.indices)` / `for (c in Enum.entries)`: an index loop over the
 * array (evaluated once). Returns false if [iterCall] is not an array iteration.
 */
internal fun Ir2Mir.arrayForLoop(
    b: IrBlock, loop: IrWhileLoop, iterCall: IrCall, out: MutableList<MirStmt>,
): Boolean {
    val recvIr = iterCall.arguments.getOrNull(0) ?: return false
    if (iterCall.symbol.owner.name.asString() != "iterator") return false
    val indicesOf = (recvIr as? IrCall)?.takeIf { it.symbol.owner.name.asString() == "<get-indices>" }?.arguments?.getOrNull(0)
    if (indicesOf != null) {
        if (arrayKindOfFq(indicesOf.type.classFqName?.asString()) == null) return false
    } else if (arrayKindOfFq(recvIr.type.classFqName?.asString()) == null) return false
    val body = loop.body as? IrBlock ?: return false
    val loopVar = body.statements.getOrNull(0) as? IrVariable ?: return false
    val label = MirLabel(labelCounter++)

    // `for (i in array.indices)`: iterator() of an IntRange built from indices
    val indicesCall = recvIr as? IrCall
    if (indicesCall != null && indicesCall.symbol.owner.name.asString() == "<get-indices>") {
        val arr = local(operands(listOf(indicesCall.arguments[0]!!), out)[0], out)
        if (arrayKindOf(arr.type) == null) return false
        loopLabels[loop] = label
        loopFinallyDepth[loop] = finallyStack.size
        val v = MirLocal(localCounter++, loopVar.name.asString(), TInt)
        locals[loopVar] = v
        val inner = ArrayList<MirStmt>()
        for (x in body.statements.drop(1)) stmt(x, inner)
        out += MirStmt.ForRange(label, v, MirExpr.IntConst(0), arrayLength(arr), null, true, false, inner)
        return true
    }

    val arrExpr = operands(listOf(recvIr), out)[0]
    val kind = arrayKindOf(arrExpr.type) ?: return false
    val arr = local(arrExpr, out)
    loopLabels[loop] = label
    loopFinallyDepth[loop] = finallyStack.size
    val idx = MirLocal(localCounter++, "idx", TInt)
    val inner = ArrayList<MirStmt>()
    val xt = mirType(loopVar.type, loopVar)
    val elem = adaptOrCoerce(arrayGet(kind, arr, MirExpr.Local(idx)), xt, b, inner)
    val xl = MirLocal(localCounter++, loopVar.name.asString(), xt)
    locals[loopVar] = xl
    inner += MirStmt.Declare(xl, elem)
    for (x in body.statements.drop(1)) stmt(x, inner)
    out += MirStmt.ForRange(label, idx, MirExpr.IntConst(0), arrayLength(arr), null, true, false, inner)
    return true
}

private fun Ir2Mir.adaptOrCoerce(e: MirExpr, t: MirType, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    if (e.type == t) return e
    val x = if (e.isOwned()) tmpOf(e, out) else e
    return coerceFlexible(x, t, at, out)
}

/** The empty array passed for a `vararg` parameter without arguments. */
internal fun Ir2Mir.emptyVararg(p: IrValueParameter, at: IrElement, out: MutableList<MirStmt>): MirExpr {
    val t = mirType(p.type, p)
    val kind = arrayKindOf(t) ?: unsupported(at, "vararg parameter of type $t")
    return tmpOf(MirExpr.RuntimeCall("mk_array_new_${kind.suffix}", listOf(MirExpr.IntConst(0)), arrayType(kind)), out)
}
