@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName

/*
 * Ir2Mir: exceptions.
 *
 * ABI (see minirt.h v1-L): error-return. `throw` and runtime failures leave the exception in the
 * thread-local mk_pending_exception; the emitter checks it after every call that can throw and
 * unwinds scope by scope (releasing locals) to the innermost `try`, or out of the function.
 *
 * Exception classes are provided by the runtime (Throwable, Exception, RuntimeException,
 * IllegalStateException, ...: `MkThrowable` layout {message, cause}); they appear in MIR as
 * external classes. User classes may extend them; slot 3 of the vtable is `Throwable.message`.
 */

internal class BuiltinClass(val cName: String, val qualified: String, val parent: String?, val names: List<String>)

private fun b(cName: String, qualified: String, parent: String?, vararg extra: String) =
    BuiltinClass(cName, qualified, parent, listOf(qualified) + extra)

internal val BUILTIN_CLASSES: List<BuiltinClass> = listOf(
    b("MkThrowable", "java.lang.Throwable", null, "kotlin.Throwable"),
    b("MkException", "java.lang.Exception", "MkThrowable", "kotlin.Exception"),
    b("MkRuntimeException", "java.lang.RuntimeException", "MkException", "kotlin.RuntimeException"),
    b("MkError", "java.lang.Error", "MkThrowable", "kotlin.Error"),
    b("MkIllegalStateException", "java.lang.IllegalStateException", "MkRuntimeException", "kotlin.IllegalStateException"),
    b("MkIllegalArgumentException", "java.lang.IllegalArgumentException", "MkRuntimeException", "kotlin.IllegalArgumentException"),
    b("MkNullPointerException", "java.lang.NullPointerException", "MkRuntimeException", "kotlin.NullPointerException"),
    b("MkArithmeticException", "java.lang.ArithmeticException", "MkRuntimeException", "kotlin.ArithmeticException"),
    b("MkClassCastException", "java.lang.ClassCastException", "MkRuntimeException", "kotlin.ClassCastException"),
    b("MkIndexOutOfBoundsException", "java.lang.IndexOutOfBoundsException", "MkRuntimeException", "kotlin.IndexOutOfBoundsException"),
    b("MkArrayIndexOutOfBoundsException", "java.lang.ArrayIndexOutOfBoundsException", "MkIndexOutOfBoundsException"),
    b("MkStringIndexOutOfBoundsException", "java.lang.StringIndexOutOfBoundsException", "MkIndexOutOfBoundsException", "kotlin.StringIndexOutOfBoundsException"),
    b("MkUnsupportedOperationException", "java.lang.UnsupportedOperationException", "MkRuntimeException", "kotlin.UnsupportedOperationException"),
    b("MkNoSuchElementException", "java.util.NoSuchElementException", "MkRuntimeException", "kotlin.NoSuchElementException"),
    b("MkNumberFormatException", "java.lang.NumberFormatException", "MkIllegalArgumentException", "kotlin.NumberFormatException"),
    b("MkUninitializedPropertyAccessException", "kotlin.UninitializedPropertyAccessException", "MkRuntimeException"),
    b("MkNotImplementedError", "kotlin.NotImplementedError", "MkError"),
    b("MkNegativeArraySizeException", "java.lang.NegativeArraySizeException", "MkRuntimeException"),
    b("MkAssertionError", "java.lang.AssertionError", "MkError", "kotlin.AssertionError"),
)

private val builtinByFq: Map<String, BuiltinClass> = BUILTIN_CLASSES.flatMap { c -> c.names.map { it to c } }.toMap()

internal fun builtinMirClasses(): List<MirClass> = BUILTIN_CLASSES.map {
    MirClass(it.qualified, it.cName, false, it.parent, emptyList(), emptyList(), emptyList(), emptyList(), null, external = true)
}

internal fun Ir2Mir.builtinOf(c: IrClass): BuiltinClass? =
    if (c in classInfos) null else builtinByFq[c.kotlinFqName.asString()]

internal fun Ir2Mir.builtinType(name: String, nullable: Boolean = false): TObject {
    val c = BUILTIN_CLASSES.first { it.cName == name }
    return TObject(c.qualified, c.cName, nullable)
}

/** `Throwable.message` getter at the root of the override chain: the vtable slot 3 of every Throwable. */
internal fun Ir2Mir.messageRootOf(c: IrClass): IrSimpleFunction? {
    builtinMessageRoot?.let { return it }
    val prop = c.declarations.filterIsInstance<IrProperty>().firstOrNull { it.name.asString() == "message" }
    val getter = prop?.getter
        ?: c.declarations.filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "<get-message>" || it.name.asString() == "getMessage" }
    val r = getter?.let { topClass(it) }
    builtinMessageRoot = r
    return r
}

/** Is [c] (a user class or a built-in) a subclass of a runtime-provided exception class? */
internal fun Ir2Mir.inheritsBuiltin(c: IrClass): Boolean {
    var k: IrClass? = c
    while (k != null) {
        if (builtinOf(k) != null) return true
        k = superclassOf(k)
    }
    return false
}

// ---- construction -----------------------------------------------------------------------------------------

private fun IrFunction.paramFq(i: Int): String? = parameters.getOrNull(i)?.type?.classFqName?.asString()

/** (message, cause) arguments of a built-in exception constructor call. */
private fun Ir2Mir.builtinCtorArgs(k: IrConstructor, args: List<MirExpr>, at: IrElement, out: MutableList<MirStmt>): Pair<MirExpr, MirExpr> {
    val nullStr = MirExpr.NullConst(TNString)
    val nullObj = MirExpr.NullConst(anyNullable)
    return when (k.parameters.size) {
        0 -> nullStr to nullObj
        1 -> if (k.paramFq(0) == "kotlin.String") args[0] to nullObj
        else {
            val cause = args[0]
            val msg = tmpOf(MirExpr.RuntimeCall("mk_throwable_cause_message", listOf(asAny(cause)), TNString), out)
            msg to cause
        }
        2 -> args[0] to args[1]
        else -> unsupported(at, "constructor of '${(k.parent as IrClass).name}' with ${k.parameters.size} parameters")
    }
}

internal fun Ir2Mir.builtinNew(c: IrConstructorCall, cls: IrClass, bc: BuiltinClass, out: MutableList<MirStmt>): MirExpr {
    val k = c.symbol.owner
    val given = c.arguments.map { it ?: unsupported(c, "omitted argument in exception constructor") }
    val vals = operands(given, out).mapIndexed { i, v -> coerceFlexible(v, mirType(k.parameters[i].type, k.parameters[i]), c, out) }
    val (msg, cause) = builtinCtorArgs(k, vals, c, out)
    return MirExpr.RuntimeCall(bc.cName + "_new", listOf(msg, asAny(cause)), builtinType(bc.cName))
}

/** `new` of a runtime exception class with a constant message. */
internal fun Ir2Mir.newBuiltin(cName: String, message: MirExpr): MirExpr =
    MirExpr.RuntimeCall(cName + "_new", listOf(message, MirExpr.NullConst(anyNullable)), builtinType(cName))

internal fun Ir2Mir.builtinSuperInit(c: IrDelegatingConstructorCall, k: IrConstructor, out: MutableList<MirStmt>) {
    val given = c.arguments.map { it ?: unsupported(c, "omitted argument in exception constructor") }
    val vals = operands(given, out).mapIndexed { i, v -> coerceFlexible(v, mirType(k.parameters[i].type, k.parameters[i]), c, out) }
    val (msg, cause) = builtinCtorArgs(k, vals, c, out)
    val self = MirExpr.Local(locals.values.first { it.name == "this" && it.id == 0 })
    out += MirStmt.Eval(MirExpr.RuntimeCall("mk_throwable_init", listOf(self, msg, asAny(cause)), TUnit))
}

/** Members of the built-in exception classes other than the ones handled by ordinary dispatch. */
internal fun Ir2Mir.builtinMember(c: IrCall, real: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr? {
    val viaSuper = c.superQualifierSymbol != null
    val name = real.name.asString()
    val args = c.arguments.map { it ?: unsupported(c, "omitted argument") }
    return when {
        name == "<get-cause>" -> {
            val r = operands(args, out)[0]
            MirExpr.RuntimeCall("mk_throwable_cause", listOf(asAny(r)), TObject("java.lang.Throwable", "MkThrowable", true))
        }
        name == "printStackTrace" && args.size == 1 -> {
            val r = operands(args, out)[0]
            MirExpr.RuntimeCall("mk_throwable_print_stack_trace", listOf(asAny(r)), TUnit)
        }
        viaSuper && name == "toString" -> MirExpr.RuntimeCall("mk_throwable_to_string", listOf(asAny(operands(args, out)[0])), TString)
        viaSuper && name == "<get-message>" -> MirExpr.RuntimeCall("mk_throwable_message", listOf(asAny(operands(args, out)[0])), TNString)
        name == "<get-message>" || name == "toString" || name == "equals" || name == "hashCode" -> null
        else -> unsupported(c, "call to '${(real.parent as IrClass).kotlinFqName}.$name'")
    }
}

// ---- throw / try ----------------------------------------------------------------------------------------------

internal fun Ir2Mir.throwStmt(s: IrThrow, out: MutableList<MirStmt>) {
    val v = value(s.value, out)
    out += MirStmt.Throw(v)
}

/** Runs the `finally` blocks entered since depth [downTo] (innermost first), before leaving through a jump. */
internal fun Ir2Mir.runFinalizers(downTo: Int, out: MutableList<MirStmt>) {
    if (finallyStack.size <= downTo) return
    val saved = finallyStack
    val entries = saved.drop(downTo)
    try {
        for (i in entries.indices.reversed()) {
            finallyStack = ArrayList(saved.subList(0, downTo + i))
            stmt(entries[i], out)
        }
    } finally {
        finallyStack = saved
    }
}

internal fun Ir2Mir.tryValue(t: IrTry, out: MutableList<MirStmt>, asStatement: Boolean = false): MirExpr {
    val type = mirType(t.type, t)
    val wantValue = !asStatement && type != TUnit && type != TNothing
    val res = if (wantValue) newTemp(type) else null
    if (res != null) out += MirStmt.Declare(res, null)

    fun branch(e: IrExpression, into: MutableList<MirStmt>) {
        if (res != null && e.type.classFqName?.asString() != "kotlin.Nothing") into += MirStmt.Assign(res, bindValue(e, type, into))
        else stmt(e, into)
    }

    val fin = t.finallyExpression
    if (fin != null) finallyStack.add(fin)
    val inner = ArrayList<MirStmt>()
    val body = ArrayList<MirStmt>()
    branch(t.tryResult, body)
    val clauses = ArrayList<MirCatch>()
    for (c in t.catches) {
        val p = c.catchParameter
        val pt = mirType(p.type, p)
        if (pt !is TObject) unsupported(c, "catch parameter of type $pt")
        val l = MirLocal(localCounter++, p.name.asString(), pt)
        locals[p] = l
        val cb = ArrayList<MirStmt>()
        cb += MirStmt.Declare(l, MirExpr.CaughtException(pt))
        branch(c.result, cb)
        clauses += MirCatch(pt, cb)
    }
    if (fin != null) finallyStack.removeAt(finallyStack.size - 1)
    if (clauses.isEmpty()) inner += body else inner += MirStmt.Try(body, clauses)

    if (fin == null) {
        out += inner
    } else {
        // finally: exceptional path = catch-all that runs the finalizer and rethrows, normal path = finalizer after
        val ex = MirLocal(localCounter++, "exc", builtinType("MkThrowable"))
        val handler = ArrayList<MirStmt>()
        handler += MirStmt.Declare(ex, MirExpr.CaughtException(ex.type))
        stmt(fin, handler)
        handler += MirStmt.Throw(MirExpr.Local(ex))
        out += MirStmt.Try(inner, listOf(MirCatch(ex.type, handler)))
        stmt(fin, out)
    }
    return if (res != null) MirExpr.Local(res) else if (type == TNothing) MirExpr.Unreachable(TNothing) else MirExpr.UnitValue
}
