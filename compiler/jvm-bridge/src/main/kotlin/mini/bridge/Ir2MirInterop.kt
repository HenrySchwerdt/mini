@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.CFieldSpec
import mini.backend.CInterop
import mini.backend.CStructLayout
import mini.backend.Mangling
import mini.backend.MirCatch
import mini.backend.MirExpr
import mini.backend.MirExtern
import mini.backend.MirFunction
import mini.backend.MirLocal
import mini.backend.MirStmt
import mini.backend.MirType
import mini.backend.TBoolean
import mini.backend.TByte
import mini.backend.TDouble
import mini.backend.TFloat
import mini.backend.TInt
import mini.backend.TLong
import mini.backend.TNothing
import mini.backend.TPtr
import mini.backend.TShort
import mini.backend.TUnit
import mini.backend.isInteropScalar
import mini.backend.isRef
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.kotlinFqName

/*
 * Ir2Mir: C interop (docs/plan-kotlin.md section 7, compiler/README.md "C interop"). The own frontend's port is
 * compiler/lower/.../Ir2MirInterop.kt; both must produce the same MIR.
 *
 *  - `CPointer<T>`, `NativePtr`, every `CPointed` (`IntVar`, a `@CStruct` class) and `CFunction` are the MIR type Ptr;
 *    their classes never become MIR classes;
 *  - `external fun` with `@CName("sym")` (or, in a user file, the function's own name) is a direct C call (MirExpr.CCall);
 *  - the intrinsics of kotlinx.cinterop/Intrinsics.kt become loads, stores, pointer arithmetic, constants and function
 *    pointers; `@CStruct` property accessors become loads and stores at the offsets the C layout rules give.
 */

private const val CINTEROP_PKG = "kotlinx.cinterop"
private const val CPOINTER = "kotlinx.cinterop.CPointer"
private const val CSTRUCT = "kotlinx.cinterop.CStruct"
private const val CARRAY = "kotlinx.cinterop.CArray"
private const val CVARIADIC = "kotlinx.cinterop.CVariadic"
private const val CINCLUDE = "kotlinx.cinterop.CInclude"
private const val CLINK = "kotlinx.cinterop.CLink"
private const val CNAME = "kotlin.native.CName"

/** What the compiler knows about a `@CStruct` class: its layout and the properties (fields) in declaration order. */
internal class StructInfo(val layout: CStructLayout, val names: List<String>, val arrays: List<Boolean>)

private fun List<IrConstructorCall>.anno(fq: String): IrConstructorCall? = firstOrNull { it.type.classFqName?.asString() == fq }

private fun IrConstructorCall.stringArg(i: Int): String? = (arguments.getOrNull(i) as? IrConst)?.value as? String

private fun IrConstructorCall.longArg(i: Int): Long? = when (val v = (arguments.getOrNull(i) as? IrConst)?.value) {
    is Int -> v.toLong()
    is Long -> v
    else -> null
}

/** The string argument of every `@file:<annotation>` of the module's files (`@file:CInclude("h")`, `@file:CLink("m")`), without duplicates. */
internal fun Ir2Mir.interopFileAnnotations(module: org.jetbrains.kotlin.ir.declarations.IrModuleFragment, fq: String): List<String> {
    val out = ArrayList<String>()
    for (f in module.files) for (a in f.annotations) {
        if (a.type.classFqName?.asString() != fq) continue
        val s = a.stringArg(0) ?: continue
        if (s !in out) out += s
    }
    return out
}

// ---- classes ----------------------------------------------------------------------------------------------

/** `CPointer`, `NativePtr`, `CPointed` and everything that extends it: represented by an address, never a MIR class. */
internal fun Ir2Mir.isPtrClass(c: IrClass): Boolean {
    ptrClassCache[c]?.let { return it }
    val fq = c.kotlinFqName.asString()
    var r = fq == CPOINTER || fq == "kotlinx.cinterop.NativePtr" || fq == "kotlinx.cinterop.CPointed"
    if (!r) for (t in c.superTypes) {
        val s = t.classOrNull?.owner ?: continue
        if (isPtrClass(s)) { r = true; break }
    }
    ptrClassCache[c] = r
    return r
}

internal fun Ir2Mir.isStructClass(c: IrClass): Boolean = c.annotations.anno(CSTRUCT) != null

/** The type argument [t] with the type parameters of the inline functions being expanded substituted. */
internal fun Ir2Mir.substituted(t: IrType): IrType {
    var cur = t
    var guard = 0
    while (guard < 32) {
        guard++
        val sym = (cur as? IrSimpleType)?.classifier as? IrTypeParameterSymbol ?: return cur
        cur = typeSubst[sym] ?: return cur
    }
    return cur
}

// ---- layout ----------------------------------------------------------------------------------------------------

/** Size and alignment of the C variable a `CVariable` class stands for (`sizeOf<T>()`). */
internal fun Ir2Mir.varSizeAlign(cls: IrClass, at: IrElement): Pair<Long, Int> {
    return when (cls.kotlinFqName.asString()) {
        "kotlinx.cinterop.ByteVar", "kotlinx.cinterop.BooleanVar" -> 1L to 1
        "kotlinx.cinterop.ShortVar" -> 2L to 2
        "kotlinx.cinterop.IntVar", "kotlinx.cinterop.FloatVar" -> 4L to 4
        "kotlinx.cinterop.LongVar", "kotlinx.cinterop.DoubleVar", "kotlinx.cinterop.CPointerVar" -> 8L to 8
        else -> {
            if (!isStructClass(cls)) unsupported(at, "sizeOf of '${cls.kotlinFqName}' (only the C variable types and @CStruct classes have a C size)")
            val s = structOf(cls, at)
            s.layout.size to s.layout.align
        }
    }
}

internal fun Ir2Mir.structOf(cls: IrClass, at: IrElement): StructInfo {
    structCache[cls]?.let { return it }
    val a = cls.annotations.anno(CSTRUCT) ?: unsupported(at, "'${cls.kotlinFqName}' is not a @CStruct class")
    val names = ArrayList<String>()
    val arrays = ArrayList<Boolean>()
    val specs = ArrayList<CFieldSpec>()
    for (d in cls.declarations) {
        if (d !is IrProperty) continue
        val name = d.name.asString()
        val type = d.getter?.returnType ?: d.backingField?.type ?: unsupported(d, "struct field '$name' without a type")
        val arr = d.annotations.anno(CARRAY)
        var size: Long
        var align: Int
        if (arr != null) {
            val n = arr.longArg(0) ?: unsupported(d, "@CArray needs a constant length")
            val elem = (type as? IrSimpleType)?.arguments?.firstOrNull()?.let { (it as? org.jetbrains.kotlin.ir.types.IrTypeProjection)?.type }
            val ec = elem?.classOrNull?.owner ?: unsupported(d, "struct field '$name': @CArray needs a CPointer<element type> property")
            val es = varSizeAlign(ec, d)
            size = es.first * n
            align = es.second
        } else {
            val tc = type.classOrNull?.owner
            if (tc != null && isStructClass(tc) && !type.isMarkedNullable()) {
                val s = varSizeAlign(tc, d)
                size = s.first
                align = s.second
            } else {
                val mt = mirType(type, d)
                size = CInterop.scalarSize(mt)
                if (size < 0L) unsupported(d, "struct field '$name' of type '${type.classFqName?.asString() ?: type}' has no C representation")
                align = size.toInt()
            }
        }
        names += name
        arrays += (arr != null)
        specs += CFieldSpec(name, size, align)
    }
    val layout = CInterop.layout(specs, a.longArg(1) ?: CInterop.UNSET, (a.longArg(2) ?: CInterop.UNSET).toInt())
    val info = StructInfo(layout, names, arrays)
    structCache[cls] = info
    return info
}

// ---- calls ------------------------------------------------------------------------------------------------------

private fun IrSimpleFunction.cNameAnnotation(): IrConstructorCall? =
    annotations.anno(CNAME) ?: correspondingPropertySymbol?.owner?.annotations?.anno(CNAME)

private fun IrFile.isUserPackage(): Boolean {
    val p = packageFqName.asString()
    return !(p == "kotlin" || p.startsWith("kotlin.") || p.startsWith("kotlinx.") || p.startsWith("mini.") || p == "java" || p.startsWith("java."))
}

/**
 * Calls the C interop machinery handles: external functions of C, the intrinsics of kotlinx.cinterop and the property
 * accessors of `@CStruct` classes. Null for every other call.
 */
internal fun Ir2Mir.interopCall(c: IrCall, owner: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr? {
    val parent = owner.parent
    if (parent is IrFile && owner.isExternal) {
        val cn = owner.cNameAnnotation()
        if (cn != null) return externCall(c, owner, cn.stringArg(0)?.takeIf { it.isNotEmpty() } ?: owner.name.asString(), out)
        if (Reachability.isBuiltinFile(parent) && parent.packageFqName.asString() == CINTEROP_PKG) return intrinsicCall(c, owner, out)
        if (parent.isUserPackage() && owner.correspondingPropertySymbol == null) return externCall(c, owner, owner.name.asString(), out)
    }
    if (parent is IrClass && isStructClass(parent) && owner.correspondingPropertySymbol != null) return structAccess(c, owner, parent, out)
    return null
}

private fun Ir2Mir.externCall(c: IrCall, f: IrSimpleFunction, symbol: String, out: MutableList<MirStmt>): MirExpr {
    val ptypes = f.parameters.map { p ->
        if (p.varargElementType != null) unsupported(p, "external fun '${f.name}': vararg parameter '${p.name}' (declare the parameters of each call with @CVariadic instead)")
        val t = mirType(p.type, p)
        if (!CInterop.isCType(t, false)) unsupported(p, "external fun '${f.name}': parameter '${p.name}' of type '${p.type.classFqName?.asString() ?: p.type}' has no C representation (use a primitive, CPointer<...> or CValues)")
        t
    }
    val rt = mirType(f.returnType, f)
    if (!CInterop.isCType(rt, true)) unsupported(f, "external fun '${f.name}': result of type '${f.returnType.classFqName?.asString() ?: f.returnType}' has no C representation")
    val fixed = f.annotations.anno(CVARIADIC)?.longArg(0)?.toInt() ?: -1
    if (fixed > ptypes.size) unsupported(f, "external fun '${f.name}': @CVariadic(fixed = $fixed) but only ${ptypes.size} parameters")
    val args = callArgs(c, f, out)
    val extern = internExtern(MirExtern(symbol, ptypes, rt, fixed))
    return MirExpr.CCall(extern, args)
}

internal fun Ir2Mir.internExtern(x: MirExtern): MirExtern {
    val key = x.prototypeKey + "|" + x.params.joinToString(",") + "|" + x.fixedParams
    return cExternIndex.getOrPut(key) {
        cExterns += x
        x
    }
}

private fun Ir2Mir.structAccess(c: IrCall, f: IrSimpleFunction, cls: IrClass, out: MutableList<MirStmt>): MirExpr {
    val prop = f.correspondingPropertySymbol!!.owner
    val s = structOf(cls, c)
    val idx = s.names.indexOf(prop.name.asString())
    if (idx < 0) unsupported(c, "struct field '${prop.name}'")
    val offset = s.layout.offsets[idx]
    val args = c.arguments.map { it ?: unsupported(c, "omitted argument") }
    val ops = operands(args, out)
    val addr = ptrAdd(ops[0], offset)
    val propType = prop.getter?.returnType ?: prop.backingField?.type ?: unsupported(c, "struct field without a type")
    val isGet = f.name.asString().startsWith("<get-")
    val embedded = s.arrays[idx] || propType.classOrNull?.owner?.let { isStructClass(it) } == true
    if (isGet) {
        if (embedded) return MirExpr.PtrAdd(ops[0], MirExpr.LongConst(offset)).let { if (offset == 0L) ops[0] else it }
        return MirExpr.Load(addr, mirType(propType, prop))
    }
    if (embedded) unsupported(c, "assignment to the struct field '${prop.name}' (an embedded struct or array has no setter)")
    out += MirStmt.Store(addr, coerceOperand(ops[1], mirType(propType, prop), c, out))
    return MirExpr.UnitValue
}

internal fun ptrAdd(p: MirExpr, offset: Long): MirExpr = if (offset == 0L) p else MirExpr.PtrAdd(p, MirExpr.LongConst(offset))

private fun addr(p: MirExpr, off: MirExpr): MirExpr = if (off is MirExpr.LongConst && off.value == 0L) p else MirExpr.PtrAdd(p, off)

private fun Ir2Mir.intrinsicCall(c: IrCall, f: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr {
    val name = f.name.asString()
    val raw = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$name'") }
    if (name == "staticCFunction") return staticCFunction(c, raw, out)
    val v = operands(raw, out)
    val rt = mirType(c.type, c)
    return when (name) {
        "<get-rawPtr>", "<get-rawValue>", "interpretPointed", "interpretNullablePointed", "interpretCPointer", "mkCPointer" -> retype(v[0], rt)
        "plus" -> addr(v[0], v[1])
        "toLong" -> MirExpr.Convert(v[0], TLong)
        "toNativePtr" -> MirExpr.Convert(v[0], TPtr(false))
        "sizeOf", "alignOf" -> {
            val ta = c.typeArguments.firstOrNull()?.let { substituted(it) } ?: unsupported(c, "$name without a type argument")
            val cls = ta.classOrNull?.owner ?: unsupported(c, "$name of '${ta.classFqName?.asString() ?: ta}'")
            val sa = varSizeAlign(cls, c)
            MirExpr.LongConst(if (name == "sizeOf") sa.first else sa.second.toLong())
        }
        "invoke" -> {
            val argTypes = v.drop(1).map { a ->
                if (!CInterop.isCType(a.type, false)) unsupported(c, "call of a C function pointer with an argument of type ${a.type}")
                a.type
            }
            if (!CInterop.isCType(rt, true)) unsupported(c, "call of a C function pointer with a result of type $rt")
            MirExpr.CCallIndirect(v[0], v.drop(1), argTypes, rt)
        }
        "mkLoadByte" -> MirExpr.Load(addr(v[0], v[1]), TByte)
        "mkLoadShort" -> MirExpr.Load(addr(v[0], v[1]), TShort)
        "mkLoadInt" -> MirExpr.Load(addr(v[0], v[1]), TInt)
        "mkLoadLong" -> MirExpr.Load(addr(v[0], v[1]), TLong)
        "mkLoadFloat" -> MirExpr.Load(addr(v[0], v[1]), TFloat)
        "mkLoadDouble" -> MirExpr.Load(addr(v[0], v[1]), TDouble)
        "mkLoadBoolean" -> MirExpr.Load(addr(v[0], v[1]), TBoolean)
        "mkLoadPtr" -> MirExpr.Load(addr(v[0], v[1]), TPtr(true))
        "mkStoreByte", "mkStoreShort", "mkStoreInt", "mkStoreLong", "mkStoreFloat", "mkStoreDouble", "mkStoreBoolean", "mkStorePtr" -> {
            out += MirStmt.Store(addr(v[0], v[1]), v[2])
            MirExpr.UnitValue
        }
        else -> unsupported(c, "call to the C interop function '$name'")
    }
}

/** The same address as another pointer type (the nullability is a Kotlin-level fact). */
internal fun retype(e: MirExpr, to: MirType): MirExpr = e

// ---- staticCFunction ------------------------------------------------------------------------------------------------

private fun unwrapCasts(e: IrExpression): IrExpression {
    var x = e
    while (x is IrTypeOperatorCall && (x.operator == IrTypeOperator.IMPLICIT_CAST || x.operator == IrTypeOperator.IMPLICIT_NOTNULL)) x = x.argument
    return x
}

/**
 * `staticCFunction { a: Int, b: Int -> a + b }`: the lambda becomes a function of its own with the C calling convention
 * (no closure object); the value is its address. A lambda that captures a variable, or a reference to anything but a
 * top-level function, is rejected. An exception that would leave the function ends the program instead.
 */
private fun Ir2Mir.staticCFunction(c: IrCall, raw: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
    val arg = unwrapCasts(raw[0])
    val n = lambdaCounter++
    if (arg is IrFunctionExpression) {
        val fn = arg.function
        val caps = closure.capturesOf(fn)
        if (caps.isNotEmpty()) {
            unsupported(arg, "staticCFunction: the lambda captures ${caps.size} variable(s) from the enclosing function; a C function pointer cannot carry state (move the state into a global or pass it through a C void* argument)")
        }
        val ptypes = fn.parameters.map { p -> cCallbackType(p.type, p, false) }
        val ret = cCallbackType(fn.returnType, fn, true)
        val cName = Mangling.function("", "${currentFunctionName()}\$cfun\$$n", ptypes)
        val mf = inFreshFunction {
            currentFunction = fn
            currentReturn = ret
            unitAsNull = false
            val params = ArrayList<MirLocal>()
            for (p in fn.parameters) {
                val l = MirLocal(localCounter++, p.name.asString(), mirType(p.type, p))
                params += l
                locals[p] = l
            }
            val body = ArrayList<MirStmt>()
            convertBodyOf(fn, ret, body)
            if (body.lastOrNull() !is MirStmt.Return) body += if (ret == TUnit) MirStmt.Return(null) else MirStmt.Panic("unreachable: staticCFunction body without a result")
            MirFunction("${currentFunctionName()}.<cfun>", cName, params, ret, guardCallback(body), null)
        }
        extraFunctions += mf
        return MirExpr.FuncAddr(cName)
    }
    if (arg is IrFunctionReference) {
        val target = arg.symbol.owner
        if (target !is IrSimpleFunction || target.parent !is IrFile || target.isExternal) unsupported(arg, "staticCFunction: only a lambda literal or a reference to a top-level function with a body can become a C function")
        val cName = functionNames[target] ?: unsupported(arg, "staticCFunction: reference to function '${target.name}'")
        val ptypes = target.parameters.map { p -> cCallbackType(p.type, p, false) }
        val ret = cCallbackType(target.returnType, target, true)
        val wrapper = Mangling.function("", "${currentFunctionName()}\$cfun\$$n", ptypes)
        val params = ptypes.mapIndexed { i, t -> MirLocal(i, "p$i", t) }
        val call = MirExpr.Call(cName, params.map { MirExpr.Local(it) }, ret)
        val body: List<MirStmt> = if (ret == TUnit) listOf(MirStmt.Eval(call), MirStmt.Return(null)) else listOf(MirStmt.Return(call))
        extraFunctions += MirFunction("${target.name}.<cfun>", wrapper, params, ret, guardCallback(body), null)
        return MirExpr.FuncAddr(wrapper)
    }
    unsupported(arg, "staticCFunction: the argument must be a lambda literal or a reference to a top-level function")
}

private fun Ir2Mir.cCallbackType(t: IrType, at: IrElement, asResult: Boolean): MirType {
    val m = mirType(t, at)
    if (!CInterop.isCType(m, asResult)) unsupported(at, "staticCFunction: type '${t.classFqName?.asString() ?: t}' has no C representation (use a primitive or a pointer)")
    return m
}

/** The body wrapped so that an exception ends the program (it cannot travel through C frames). */
private fun Ir2Mir.guardCallback(body: List<MirStmt>): List<MirStmt> {
    val t = builtinType("MkThrowable")
    val e = MirLocal(localCounter++, "ex", t)
    val handler = listOf<MirStmt>(MirStmt.Declare(e, MirExpr.CaughtException(t)), MirStmt.Panic("uncaught exception in a staticCFunction callback"))
    return listOf(MirStmt.Try(body, listOf(MirCatch(t, handler))))
}
