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

/* Ir2Mir: function bodies, statements, constructors and field initialisation. */

// ---- functions --------------------------------------------------------------------

internal fun Ir2Mir.convertFunction(f: IrFunction, cName: String, qualifiedName: String, ctorOf: String?): MirFunction {
    locals = HashMap()
    localCounter = 0
    currentFunction = f
    val params = ArrayList<MirLocal>()
    if (f is IrConstructor) {
        val cls = f.parent as IrClass
        val self = MirLocal(localCounter++, "this", typeOf(cls))
        params += self
        f.parent.let { (it as IrClass).thisReceiver }?.let { locals[it] = self }
        if (cls.kind == ClassKind.ENUM_CLASS) {
            params += MirLocal(localCounter++, "\$name", TString)
            params += MirLocal(localCounter++, "\$ordinal", TInt)
        }
    }
    for (p in f.parameters) {
        val l = MirLocal(localCounter++, if (p.kind == IrParameterKind.DispatchReceiver) "this" else p.name.asString(), mirType(p.type, p))
        locals[p] = l
        params += l
    }
    val ret = if (f is IrConstructor) TUnit else mirType(f.returnType, f)
    currentReturn = ret
    val out = ArrayList<MirStmt>()
    if (f is IrConstructor && (f.parent as IrClass).kind == ClassKind.ENUM_CLASS) {
        val cn = info(f.parent as IrClass).cName
        out += MirStmt.SetField(MirExpr.Local(params[0]), cn, "\$name", TString, MirExpr.Local(params[1]))
        out += MirStmt.SetField(MirExpr.Local(params[0]), cn, "\$ordinal", TInt, MirExpr.Local(params[2]))
    }
    when (val body = f.body) {
        is IrBlockBody -> for (s in body.statements) stmt(s, out)
        is IrExpressionBody -> {
            val e = body.expression
            if (ret == TUnit) stmt(e, out) else returnValue(e, out)
        }
        else -> unsupported(f, "function body")
    }
    return MirFunction(qualifiedName, cName, params, ret, out, ctorOf)
}

internal fun Ir2Mir.newTemp(type: MirType, name: String = "tmp"): MirLocal = MirLocal(localCounter++, name, type)

/** Stores [v] (owned or borrowed) in a fresh temporary that lives until the end of the scope. */
internal fun Ir2Mir.tmpOf(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    val t = newTemp(v.type)
    out += MirStmt.Declare(t, v)
    ownedTemps += t
    return MirExpr.Local(t)
}

/** If [v] is a single-use temporary just declared by [tmpOf], takes its initialiser back (saves a retain/release pair). */
internal fun Ir2Mir.consumeOwned(v: MirExpr, out: MutableList<MirStmt>): MirExpr {
    if (v is MirExpr.Local && v.local in ownedTemps) {
        val last = out.lastOrNull()
        val init = (last as? MirStmt.Declare)?.init
        if (last is MirStmt.Declare && last.local === v.local && init != null) {
            out.removeAt(out.size - 1)
            ownedTemps -= v.local
            return init
        }
    }
    return v
}

internal fun Ir2Mir.returnValue(e: IrExpression, out: MutableList<MirStmt>) {
    val v = bindValue(e, currentReturn, out)
    if (v.type == TNothing) return
    out += MirStmt.Return(v)
}

// ---- statements -------------------------------------------------------------------

internal fun Ir2Mir.stmt(s: IrStatement, out: MutableList<MirStmt>) {
    when (s) {
        is IrVariable -> {
            if (s.isLateinit) unsupported(s, "lateinit variable '${s.name}'")
            val t = mirType(s.type, s)
            if (t == TUnit || t == TNothing) unsupported(s, "variable '${s.name}' of type $t")
            val init = s.initializer?.let { bindValue(it, t, out) }
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
            out += MirStmt.Assign(l, bindValue(s.value, l.type, out))
        }
        is IrSetField -> setField(s, out)
        is IrInstanceInitializerCall -> instanceInitializer(s, out)
        is IrDelegatingConstructorCall -> delegatingCall(s, out)
        is IrEnumConstructorCall -> {}
        is IrTypeOperatorCall -> {
            if (s.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) stmt(s.argument, out)
            else discard(value(s, out), out)
        }
        is IrGetObjectValue -> {
            if (s.symbol.owner.kotlinFqName.asString() != "kotlin.Unit") discard(value(s, out), out)
        }
        is IrCall -> if (isNoWhenBranch(s)) out += MirStmt.Panic("NoWhenBranchMatchedException") else out += MirStmt.Eval(call(s, out))
        is IrExpression -> discard(value(s, out), out)
        else -> unsupported(s, describe(s))
    }
}

internal fun Ir2Mir.discard(v: MirExpr, out: MutableList<MirStmt>) {
    if (v is MirExpr.Local || v === MirExpr.UnitValue) return
    out += MirStmt.Eval(v)
}

/** The synthetic `else` branch of an exhaustive `when` without `else`. */
internal fun Ir2Mir.isNoWhenBranch(c: IrCall): Boolean {
    val o = c.symbol.owner
    return o.name.asString() == "noWhenBranchMatchedException" &&
        (o.parent as? IrPackageFragment)?.packageFqName?.asString() == "kotlin.internal.ir"
}

internal fun Ir2Mir.returnStmt(s: IrReturn, out: MutableList<MirStmt>) {
    val f = currentFunction!!
    if (s.returnTargetSymbol.owner !== f) unsupported(s, "return from a lambda or nested function")
    if (currentReturn == TUnit) {
        stmt(s.value, out)
        out += MirStmt.Return(null)
    } else {
        returnValue(s.value, out)
    }
}

internal fun Ir2Mir.labelOf(loop: IrLoop, at: IrElement): MirLabel =
    loopLabels[loop] ?: unsupported(at, "jump to a loop outside the current function")

internal fun Ir2Mir.localOf(owner: Any, at: IrElement): MirLocal =
    locals[owner] ?: unsupported(at, "reference to a variable that is not a local of the current function")

// ---- classes: constructors and fields -------------------------------------------------------

internal fun Ir2Mir.fieldOwner(f: IrField): ClassInfo = info(f.parent as? IrClass ?: unsupported(f, "top-level field"))

internal fun Ir2Mir.fieldGet(recv: MirExpr, f: IrField): MirExpr =
    MirExpr.FieldGet(recv, fieldOwner(f).cName, f.name.asString(), mirType(f.type, f))

internal fun Ir2Mir.setField(s: IrSetField, out: MutableList<MirStmt>) {
    val f = s.symbol.owner
    val r = s.receiver ?: unsupported(s, "static field")
    val ops = operands(listOf(r), out)
    val ft = mirType(f.type, f)
    out += MirStmt.SetField(ops[0], fieldOwner(f).cName, f.name.asString(), ft, bindValue(s.value, ft, out))
}

internal fun Ir2Mir.instanceInitializer(s: IrInstanceInitializerCall, out: MutableList<MirStmt>) {
    val cls = s.classSymbol.owner
    val self = MirExpr.Local(localOf(cls.thisReceiver ?: unsupported(s, "class without this"), s))
    for (d in cls.declarations) {
        when (d) {
            is IrProperty -> d.backingField?.let { initField(self, it, out) }
            is IrField -> initField(self, d, out)
            is IrAnonymousInitializer -> for (x in d.body.statements) stmt(x, out)
            else -> {}
        }
    }
}

internal fun Ir2Mir.initField(self: MirExpr, f: IrField, out: MutableList<MirStmt>) {
    val e = f.initializer?.expression ?: return
    val ft = mirType(f.type, f)
    out += MirStmt.SetField(self, fieldOwner(f).cName, f.name.asString(), ft, bindValue(e, ft, out))
}

internal fun Ir2Mir.delegatingCall(c: IrDelegatingConstructorCall, out: MutableList<MirStmt>) {
    val k = c.symbol.owner
    val cls = k.parent as IrClass
    if (isAny(cls)) return
    val cName = functionNames[k] ?: unsupported(c, "call of constructor of '${cls.kotlinFqName}'")
    val self = MirExpr.Local(locals.values.first { it.name == "this" && it.id == 0 })
    val args = callArgs(c, k, out)
    out += MirStmt.Eval(MirExpr.Call(cName, listOf<MirExpr>(self) + args, TUnit))
}

internal fun Ir2Mir.constructorCall(c: IrConstructorCall, out: MutableList<MirStmt>): MirExpr {
    val k = c.symbol.owner
    val cls = k.parent as IrClass
    val cName = functionNames[k] ?: unsupported(c, "construction of '${cls.kotlinFqName}'")
    if (cls.kind != ClassKind.CLASS) unsupported(c, "construction of ${classKind(cls)} '${cls.name}'")
    if (cls.modality == Modality.ABSTRACT || cls.modality == Modality.SEALED) unsupported(c, "construction of abstract class '${cls.name}'")
    return MirExpr.New(cName, callArgs(c, k, out), typeOf(cls))
}
