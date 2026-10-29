package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/*
 * Ir2Mir: C interop (docs/plan-kotlin.md section 7, compiler/README.md "C interop"). A port of
 * jvm-bridge/.../Ir2MirInterop.kt, which documents the design; both must produce the same MIR.
 */

private const val CINTEROP_PKG = "kotlinx.cinterop"

/** What the compiler knows about a `@CStruct` class: its layout and the properties (fields) in declaration order. */
internal class StructInfo(val layout: CStructLayout, val names: List<String>, val arrays: List<Boolean>)

/** The value of the argument [index] (named [name]) of an annotation: the named argument if present, else the positional one. */
internal fun annotationArg(a: IrAnnotation, index: Int, name: String): String? {
    var k = 0
    while (k < a.args.size) {
        if (a.argNames[k] == name) return a.args[k]
        k++
    }
    if (index < a.args.size && a.argNames[index] == null) return a.args[index]
    return null
}

private fun annotationLong(a: IrAnnotation, index: Int, name: String): Long? {
    val s = annotationArg(a, index, name) ?: return null
    return s.toLongOrNull()
}

// ---- classes ----------------------------------------------------------------------------------------------

/** `CPointer`, `NativePtr`, `CPointed` and everything that extends it: represented by an address, never a MIR class. */
internal fun Ir2Mir.isPtrClass(c: IrClass): Boolean {
    val cached = ptrClassCache[c]
    if (cached != null) return cached
    val fq = c.kotlinFqName
    var r = fq == "kotlinx.cinterop.CPointer" || fq == "kotlinx.cinterop.NativePtr" || fq == "kotlinx.cinterop.CPointed"
    if (!r) {
        for (t in c.superTypes) {
            val s = t.classOrNull
            if (s != null && isPtrClass(s)) {
                r = true
                break
            }
        }
    }
    ptrClassCache[c] = r
    return r
}

internal fun Ir2Mir.isStructClass(c: IrClass): Boolean = hasAnnotation(c.annotations, "kotlinx.cinterop.CStruct")

/** The type argument [t] with the type parameters of the inline functions being expanded substituted. */
internal fun Ir2Mir.substituted(t: Type): Type {
    var cur = t
    var guard = 0
    while (guard < 32) {
        guard++
        val tp = if (cur is TypeParamType) cur.tp else return cur
        cur = typeSubst[tp] ?: return cur
    }
    return cur
}

/** The string argument of every `@file:<annotation>` of the module's files (`@file:CInclude("h")`, `@file:CLink("m")`), without duplicates. */
internal fun Ir2Mir.interopFileAnnotations(module: IrModule, name: String): List<String> {
    val out = ArrayList<String>()
    for (f in module.files) {
        for (a in f.annotations) {
            if (a.fq != name) continue
            val s = a.arg ?: continue
            if (!out.contains(s)) out.add(s)
        }
    }
    return out
}

// ---- layout ----------------------------------------------------------------------------------------------------

/** Size and alignment of the C variable a `CVariable` class stands for (`sizeOf<T>()`). */
internal fun Ir2Mir.varSizeAlign(cls: IrClass, at: IrElement): Pair<Long, Int> {
    val n = cls.kotlinFqName
    if (n == "kotlinx.cinterop.ByteVar" || n == "kotlinx.cinterop.BooleanVar") return Pair(1L, 1)
    if (n == "kotlinx.cinterop.ShortVar") return Pair(2L, 2)
    if (n == "kotlinx.cinterop.IntVar" || n == "kotlinx.cinterop.FloatVar") return Pair(4L, 4)
    if (n == "kotlinx.cinterop.LongVar" || n == "kotlinx.cinterop.DoubleVar" || n == "kotlinx.cinterop.CPointerVar") return Pair(8L, 8)
    if (!isStructClass(cls)) unsupported(at, "sizeOf of '${cls.kotlinFqName}' (only the C variable types and @CStruct classes have a C size)")
    val s = structOf(cls, at)
    return Pair(s.layout.size, s.layout.align)
}

internal fun Ir2Mir.structOf(cls: IrClass, at: IrElement): StructInfo {
    val cached = structCache[cls]
    if (cached != null) return cached
    val a = findAnnotation(cls.annotations, "kotlinx.cinterop.CStruct") ?: unsupported(at, "'${cls.kotlinFqName}' is not a @CStruct class")
    val names = ArrayList<String>()
    val arrays = ArrayList<Boolean>()
    val specs = ArrayList<CFieldSpec>()
    for (d in cls.declarations) {
        if (d !is IrProperty) continue
        val name = d.name
        val type = d.getter?.returnType ?: d.backingField?.type ?: unsupported(d, "struct field '$name' without a type")
        val arr = findAnnotation(d.annotations, "kotlinx.cinterop.CArray")
        var size: Long
        var align: Int
        if (arr != null) {
            val n = annotationLong(arr, 0, "length") ?: unsupported(d, "@CArray needs a constant length")
            val elem = if (type is ClassType && type.args.isNotEmpty()) type.args[0].type else null
            val ec = elem?.classOrNull ?: unsupported(d, "struct field '$name': @CArray needs a CPointer<element type> property")
            val es = varSizeAlign(ec, d)
            size = es.first * n
            align = es.second
        } else {
            val tc = type.classOrNull
            if (tc != null && isStructClass(tc) && !type.isMarkedNullable()) {
                val s = varSizeAlign(tc, d)
                size = s.first
                align = s.second
            } else {
                val mt = mirType(type, d)
                size = CInterop.scalarSize(mt)
                if (size < 0L) unsupported(d, "struct field '$name' of type '${type.classFqName ?: type}' has no C representation")
                align = size.toInt()
            }
        }
        names.add(name)
        arrays.add(arr != null)
        specs.add(CFieldSpec(name, size, align))
    }
    val layout = CInterop.layout(specs, annotationLong(a, 1, "size") ?: CInterop.UNSET, (annotationLong(a, 2, "align") ?: CInterop.UNSET).toInt())
    val info = StructInfo(layout, names, arrays)
    structCache[cls] = info
    return info
}

// ---- calls ------------------------------------------------------------------------------------------------------

private fun IrSimpleFunction.cNameAnnotation(): IrAnnotation? {
    val own = findAnnotation(annotations, "kotlin.native.CName")
    if (own != null) return own
    val p = correspondingProperty ?: return null
    return findAnnotation(p.annotations, "kotlin.native.CName")
}

private fun IrFile.isUserPackage(): Boolean {
    val p = packageFqName
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
        if (cn != null) {
            val given = cn.arg
            return externCall(c, owner, if (given != null && given.length > 0) given else owner.name, out)
        }
        if (parent.isBuiltin && parent.packageFqName == CINTEROP_PKG) return intrinsicCall(c, owner, out)
        if (parent.isUserPackage() && owner.correspondingProperty == null) return externCall(c, owner, owner.name, out)
    }
    if (parent is IrClass && isStructClass(parent) && owner.correspondingProperty != null) return structAccess(c, owner, parent, out)
    return null
}

private fun Ir2Mir.externCall(c: IrCall, f: IrSimpleFunction, symbol: String, out: MutableList<MirStmt>): MirExpr {
    val ptypes = ArrayList<MirType>()
    for (p in f.parameters) {
        if (p.varargElementType != null) unsupported(p, "external fun '${f.name}': vararg parameter '${p.name}' (declare the parameters of each call with @CVariadic instead)")
        val t = mirType(p.type, p)
        if (!CInterop.isCType(t, false)) unsupported(p, "external fun '${f.name}': parameter '${p.name}' of type '${p.type.classFqName ?: p.type}' has no C representation (use a primitive, CPointer<...> or CValues)")
        ptypes.add(t)
    }
    val rt = mirType(f.returnType, f)
    if (!CInterop.isCType(rt, true)) unsupported(f, "external fun '${f.name}': result of type '${f.returnType.classFqName ?: f.returnType}' has no C representation")
    val va = findAnnotation(f.annotations, "kotlinx.cinterop.CVariadic")
    val fixed = if (va != null) (va.arg?.toIntOrNull() ?: -1) else -1
    if (fixed > ptypes.size) unsupported(f, "external fun '${f.name}': @CVariadic(fixed = $fixed) but only ${ptypes.size} parameters")
    val args = callArgs(c, f, out)
    val extern = internExtern(MirExtern(symbol, ptypes, rt, fixed))
    return MirExpr.CCall(extern, args)
}

internal fun Ir2Mir.internExtern(x: MirExtern): MirExtern {
    val key = x.prototypeKey + "|" + x.params.joinToString(",") + "|" + x.fixedParams
    val known = cExternIndex[key]
    if (known != null) return known
    cExterns.add(x)
    cExternIndex[key] = x
    return x
}

private fun Ir2Mir.structAccess(c: IrCall, f: IrSimpleFunction, cls: IrClass, out: MutableList<MirStmt>): MirExpr {
    val prop = f.correspondingProperty!!
    val s = structOf(cls, c)
    val idx = s.names.indexOf(prop.name)
    if (idx < 0) unsupported(c, "struct field '${prop.name}'")
    val offset = s.layout.offsets[idx]
    val args = ArrayList<IrExpression>()
    for (a in c.arguments) args.add(a ?: unsupported(c, "omitted argument"))
    val ops = operands(args, out)
    val addr = ptrAdd(ops[0], offset)
    val propType = prop.getter?.returnType ?: prop.backingField?.type ?: unsupported(c, "struct field without a type")
    val isGet = f.name.startsWith("<get-")
    val pc = propType.classOrNull
    val embedded = s.arrays[idx] || (pc != null && isStructClass(pc))
    if (isGet) {
        if (embedded) return ptrAdd(ops[0], offset)
        return MirExpr.Load(addr, mirType(propType, prop))
    }
    if (embedded) unsupported(c, "assignment to the struct field '${prop.name}' (an embedded struct or array has no setter)")
    out += MirStmt.Store(addr, coerceOperand(ops[1], mirType(propType, prop), c, out))
    return MirExpr.UnitValue
}

internal fun ptrAdd(p: MirExpr, offset: Long): MirExpr = if (offset == 0L) p else MirExpr.PtrAdd(p, MirExpr.LongConst(offset))

private fun addr(p: MirExpr, off: MirExpr): MirExpr = if (off is MirExpr.LongConst && off.value == 0L) p else MirExpr.PtrAdd(p, off)

private fun Ir2Mir.intrinsicCall(c: IrCall, f: IrSimpleFunction, out: MutableList<MirStmt>): MirExpr {
    val name = f.name
    val raw = ArrayList<IrExpression>()
    for (a in c.arguments) raw.add(a ?: unsupported(c, "call with omitted argument to '$name'"))
    if (name == "staticCFunction") return staticCFunction(c, raw, out)
    val v = operands(raw, out)
    val rt = mirType(c.type, c)
    if (name == "<get-rawPtr>" || name == "<get-rawValue>" || name == "interpretPointed" || name == "interpretNullablePointed" || name == "interpretCPointer" || name == "mkCPointer") return retype(v[0], rt)
    if (name == "plus") return addr(v[0], v[1])
    if (name == "toLong") return MirExpr.Convert(v[0], TLong)
    if (name == "toNativePtr") return MirExpr.Convert(v[0], TPtr(false))
    if (name == "sizeOf" || name == "alignOf") {
        val targ = if (c.typeArguments.isNotEmpty()) c.typeArguments[0] else null
        val ta = if (targ != null) substituted(targ) else unsupported(c, "$name without a type argument")
        val cls = ta.classOrNull ?: unsupported(c, "$name of '${ta.classFqName ?: ta}'")
        val sa = varSizeAlign(cls, c)
        return MirExpr.LongConst(if (name == "sizeOf") sa.first else sa.second.toLong())
    }
    if (name == "invoke") {
        val argTypes = ArrayList<MirType>()
        val rest = ArrayList<MirExpr>()
        var k = 1
        while (k < v.size) {
            val a = v[k]
            if (!CInterop.isCType(a.type, false)) unsupported(c, "call of a C function pointer with an argument of type ${a.type}")
            argTypes.add(a.type)
            rest.add(a)
            k++
        }
        if (!CInterop.isCType(rt, true)) unsupported(c, "call of a C function pointer with a result of type $rt")
        return MirExpr.CCallIndirect(v[0], rest, argTypes, rt)
    }
    return when (name) {
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

/** `staticCFunction { ... }`: see the K2 path; a lambda without captures becomes a function with the C calling convention. */
private fun Ir2Mir.staticCFunction(c: IrCall, raw: List<IrExpression>, out: MutableList<MirStmt>): MirExpr {
    val arg = unwrapCasts(raw[0])
    val n = lambdaCounter++
    if (arg is IrFunctionExpression) {
        val fn = arg.function
        val caps = closure.capturesOf(fn)
        if (caps.isNotEmpty()) {
            unsupported(arg, "staticCFunction: the lambda captures ${caps.size} variable(s) from the enclosing function; a C function pointer cannot carry state (move the state into a global or pass it through a C void* argument)")
        }
        val ptypes = ArrayList<MirType>()
        for (p in fn.parameters) ptypes.add(cCallbackType(p.type, p, false))
        val ret = cCallbackType(fn.returnType, fn, true)
        val cName = Mangling.function("", currentFunctionName() + "\$cfun\$" + n, ptypes)
        val mf = inFreshFunction {
            currentFunction = fn
            currentReturn = ret
            unitAsNull = false
            val params = ArrayList<MirLocal>()
            for (p in fn.parameters) {
                val l = MirLocal(localCounter++, p.name, mirType(p.type, p))
                params.add(l)
                locals[p] = l
            }
            val body = ArrayList<MirStmt>()
            convertBodyOf(fn, ret, body)
            if (body.lastOrNull() !is MirStmt.Return) body += if (ret == TUnit) MirStmt.Return(null) else MirStmt.Panic("unreachable: staticCFunction body without a result")
            MirFunction(currentFunctionName() + ".<cfun>", cName, params, ret, guardCallback(body), null)
        }
        extraFunctions += mf
        return MirExpr.FuncAddr(cName)
    }
    if (arg is IrFunctionReference) {
        val target = arg.target
        if (target !is IrSimpleFunction || target.parent !is IrFile || target.isExternal) unsupported(arg, "staticCFunction: only a lambda literal or a reference to a top-level function with a body can become a C function")
        val cName = functionNames[target] ?: unsupported(arg, "staticCFunction: reference to function '${target.name}'")
        val ptypes = ArrayList<MirType>()
        for (p in target.parameters) ptypes.add(cCallbackType(p.type, p, false))
        val ret = cCallbackType(target.returnType, target, true)
        val wrapper = Mangling.function("", currentFunctionName() + "\$cfun\$" + n, ptypes)
        val params = ArrayList<MirLocal>()
        val callArgs = ArrayList<MirExpr>()
        var i = 0
        while (i < ptypes.size) {
            val l = MirLocal(i, "p" + i, ptypes[i])
            params.add(l)
            callArgs.add(MirExpr.Local(l))
            i++
        }
        val call = MirExpr.Call(cName, callArgs, ret)
        val body: List<MirStmt> = if (ret == TUnit) listOf(MirStmt.Eval(call), MirStmt.Return(null)) else listOf(MirStmt.Return(call))
        extraFunctions += MirFunction(target.name + ".<cfun>", wrapper, params, ret, guardCallback(body), null)
        return MirExpr.FuncAddr(wrapper)
    }
    unsupported(arg, "staticCFunction: the argument must be a lambda literal or a reference to a top-level function")
}

private fun Ir2Mir.cCallbackType(t: Type, at: IrElement, asResult: Boolean): MirType {
    val m = mirType(t, at)
    if (!CInterop.isCType(m, asResult)) unsupported(at, "staticCFunction: type '${t.classFqName ?: t}' has no C representation (use a primitive or a pointer)")
    return m
}

/** The body wrapped so that an exception ends the program (it cannot travel through C frames). */
private fun Ir2Mir.guardCallback(body: List<MirStmt>): List<MirStmt> {
    val t = builtinType("MkThrowable")
    val e = MirLocal(localCounter++, "ex", t)
    val handler = listOf<MirStmt>(MirStmt.Declare(e, MirExpr.CaughtException(t)), MirStmt.Panic("uncaught exception in a staticCFunction callback"))
    return listOf(MirStmt.Try(body, listOf(MirCatch(t, handler))))
}
