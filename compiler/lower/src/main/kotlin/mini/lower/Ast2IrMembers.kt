package mini.lower

import mini.frontend.*
import mini.frontend.sema.*

/* Ast2Ir: members the compiler synthesizes: object constructors, data class members, enum members. */

// ---- objects ----------------------------------------------------------------------------------------------------------

/** `object` declarations and anonymous objects have a (private) primary constructor without parameters */
fun Ast2Ir.objectConstructor(c: IrClass): IrConstructor {
    val k = IrConstructor()
    k.parent = c
    k.isPrimary = true
    k.visibility = "private"
    k.returnType = irType(c.sym!!.thisType())
    k.bodyBuilder = { buildObjectCtorBody(k, c) }
    return k
}

fun Ast2Ir.buildObjectCtorBody(k: IrConstructor, c: IrClass): IrBody {
    val cs = c.sym!!
    val bc = memberCtx(k, c, localClassCtx(c))
    return withCtx(bc) {
        val stmts = ArrayList<IrStatement>()
        stmts.add(superCall(cs, c, k, null))
        stmts.add(IrInstanceInitializerCall(c, sema.unitType))
        IrBlockBody(stmts)
    }
}

// ---- data classes -------------------------------------------------------------------------------------------------------

fun Ast2Ir.addDataMembers(c: IrClass, sym: ClassSym) {
    val pc = sym.primaryCtor ?: return
    sema.ensureSig(pc)
    val props = ArrayList<IrProperty>()
    for (p in pc.primaryParams) {
        if (p.valVar == null) continue
        val l = sym.props[p.name] ?: continue
        for (x in l) if (x.ctorParam === p) props.add(propertyOf(x))
    }
    var i = 1
    for (p in props) {
        val fs = findSynth(sym, "component" + i, 0) ?: break
        c.declarations.add(componentFunction(c, sym, fs, p, i))
        i++
    }
    val copy = findSynth(sym, "copy", pc.params.size)
    if (copy != null) c.declarations.add(copyFunction(c, sym, copy, pc))
    val ts = findSynth(sym, "toString", 0)
    if (ts != null) c.declarations.add(toStringFunction(c, sym, ts, props))
    val hs = findSynth(sym, "hashCode", 0)
    if (hs != null) c.declarations.add(hashCodeFunction(c, sym, hs, props))
    val es = findSynth(sym, "equals", 1)
    if (es != null) c.declarations.add(equalsFunction(c, sym, es, props))
}

private fun Ast2Ir.findSynth(sym: ClassSym, name: String, argc: Int): FunSym? {
    val l = sym.funs[name] ?: return null
    for (f in l) {
        if (f.kind != FunKind.SYNTH) continue
        sema.ensureSig(f)
        if (f.params.size == argc) return f
    }
    return null
}

private fun Ast2Ir.synthFunction(c: IrClass, sym: ClassSym, fs: FunSym, name: String, ret: Type, operator: Boolean, open: Boolean): IrSimpleFunction {
    val f = IrSimpleFunction(name)
    f.sym = fs
    f.parent = c
    f.origin = "GENERATED_DATA_CLASS_MEMBER"
    f.modality = if (open) IrModality.OPEN else IrModality.FINAL
    f.isOperator = operator
    f.returnType = ret
    val th = IrValueParameter("<this>", irType(sym.thisType()), IrParameterKind.DispatchReceiver, 0)
    th.parent = f
    f.parameters.add(th)
    funMap[fs] = f
    return f
}

private fun Ast2Ir.thisOf(f: IrFunction): IrExpression = getValue(f.parameters[0])

private fun Ast2Ir.fieldGet(f: IrFunction, p: IrProperty): IrExpression {
    val fld = p.backingField!!
    return IrGetField(fld, thisOf(f), fld.type)
}

private fun Ast2Ir.ret(f: IrFunction, e: IrExpression): IrStatement = IrReturn(f, e, sema.nothingType)

private fun Ast2Ir.componentFunction(c: IrClass, sym: ClassSym, fs: FunSym, p: IrProperty, i: Int): IrSimpleFunction {
    val fld = p.backingField!!
    val f = synthFunction(c, sym, fs, "component" + i, fld.type, true, false)
    f.bodyBuilder = {
        withCtx(BodyCtx(null, f, null, false)) {
        val stmts = ArrayList<IrStatement>()
        stmts.add(ret(f, fieldGet(f, p)))
        IrBlockBody(stmts)
        }
    }
    return f
}

private fun Ast2Ir.copyFunction(c: IrClass, sym: ClassSym, fs: FunSym, pc: FunSym): IrSimpleFunction {
    val f = synthFunction(c, sym, fs, "copy", irType(sym.thisType()), false, false)
    val ctor = irConstructorOf(pc)
    var idx = 1
    val params = ArrayList<IrValueParameter>()
    for (cp in ctor.parameters) {
        if (cp.kind != IrParameterKind.Regular) continue
        val np = IrValueParameter(cp.name, cp.type, IrParameterKind.Regular, idx++)
        np.parent = f
        // the default is the current value of the property
        val prop = findPropByName(c, cp.name)
        if (prop != null) {
            val pp = prop
            np.defaultValueBuilder = {
                val bc = BodyCtx(null, f, null, false)
                bc.recvs.add(RecvEntry(sym, null, null, null, f.parameters[0]))
                IrExpressionBody(fieldGet(f, pp))
            }
        }
        f.parameters.add(np)
        params.add(np)
    }
    val stmts = ArrayList<IrStatement>()
    val call = IrConstructorCall(ctor, irType(sym.thisType()))
    for (cp in ctor.parameters) call.arguments.add(null)
    var k = 0
    var j = 0
    for (cp in ctor.parameters) {
        if (cp.kind == IrParameterKind.Regular) {
            call.arguments[k] = getValue(params[j])
            j++
        }
        k++
    }
    stmts.add(ret(f, call))
    f.body = IrBlockBody(stmts)
    return f
}

private fun Ast2Ir.findPropByName(c: IrClass, name: String): IrProperty? {
    for (d in c.declarations) if (d is IrProperty && d.name == name) return d
    return null
}

private fun Ast2Ir.toStringFunction(c: IrClass, sym: ClassSym, fs: FunSym, props: List<IrProperty>): IrSimpleFunction {
    val f = synthFunction(c, sym, fs, "toString", sema.stringType, false, true)
    val parts = ArrayList<IrExpression>()
    parts.add(IrConst(sema.stringType, sym.name + "("))
    var first = true
    for (p in props) {
        if (!first) parts.add(IrConst(sema.stringType, ", "))
        first = false
        parts.add(IrConst(sema.stringType, p.name + "="))
        parts.add(fieldGet(f, p))
    }
    parts.add(IrConst(sema.stringType, ")"))
    f.bodyBuilder = {
        withCtx(BodyCtx(null, f, null, false)) {
        val stmts = ArrayList<IrStatement>()
        stmts.add(ret(f, IrStringConcatenation(parts, sema.stringType)))
        IrBlockBody(stmts)
        }
    }
    return f
}

private fun Ast2Ir.hashCodeFunction(c: IrClass, sym: ClassSym, fs: FunSym, props: List<IrProperty>): IrSimpleFunction {
    val f = synthFunction(c, sym, fs, "hashCode", sema.intType, false, true)
    f.bodyBuilder = {
        withCtx(BodyCtx(null, f, null, false)) {
            val stmts = ArrayList<IrStatement>()
            if (props.isEmpty()) {
                stmts.add(ret(f, constInt(0)))
            } else {
                val result = IrVariable("result", sema.intType, true)
                result.parent = f
                var i = 0
                for (p in props) {
                    val fld = p.backingField!!
                    val h = hashOf(f, fieldGet(f, p), fld.type)
                    if (i == 0) {
                        result.initializer = h
                        stmts.add(result)
                    } else {
                        val times = IrCall(memberFunNamed(sema.intType, "times", 1), sema.intType)
                        times.arguments.add(getValue(result))
                        times.arguments.add(constInt(31))
                        val plus = IrCall(memberFunNamed(sema.intType, "plus", 1), sema.intType)
                        plus.arguments.add(times)
                        plus.arguments.add(h)
                        stmts.add(IrSetValue(result, plus, sema.unitType, "EQ"))
                    }
                    i++
                }
                stmts.add(ret(f, getValue(result)))
            }
            IrBlockBody(stmts)
        }
    }
    return f
}

private fun Ast2Ir.hashOf(f: IrFunction, value: IrExpression, t: Type): IrExpression {
    if (t.nullable) {
        // `if (x == null) 0 else x.hashCode()`
        val tmp = newTemp("hash", t, value)
        val call = IrCall(memberFunNamed(ops.makeNotNull(t), "hashCode", 0), sema.intType)
        call.arguments.add(IrTypeOperatorCall(IrTypeOperator.IMPLICIT_CAST, irType(ops.makeNotNull(t)), getValue(tmp), irType(ops.makeNotNull(t))))
        val br = ArrayList<IrBranch>()
        br.add(IrBranch(eqNull(getValue(tmp)), constInt(0)))
        br.add(IrBranch(constBool(true), call))
        val stmts = ArrayList<IrStatement>()
        stmts.add(tmp)
        stmts.add(IrWhen(br, sema.intType, null))
        return IrBlock(stmts, sema.intType, null)
    }
    val call = IrCall(memberFunNamed(t, "hashCode", 0), sema.intType)
    call.arguments.add(value)
    return call
}

private fun Ast2Ir.equalsFunction(c: IrClass, sym: ClassSym, fs: FunSym, props: List<IrProperty>): IrSimpleFunction {
    val f = synthFunction(c, sym, fs, "equals", sema.booleanType, true, true)
    val other = IrValueParameter("other", sema.nullableAny, IrParameterKind.Regular, 1)
    other.parent = f
    f.parameters.add(other)
    val unit = sema.unitType
    f.bodyBuilder = {
        withCtx(BodyCtx(null, f, null, false)) {
        val stmts = ArrayList<IrStatement>()
        val self = irType(sym.thisType())
        // if (this === other) return true
        run {
            val eq = IrCall(builtinFun("EQEQEQ"), sema.booleanType)
            eq.origin = "EQEQEQ"
            eq.arguments.add(thisOf(f))
            eq.arguments.add(getValue(other))
            val br = ArrayList<IrBranch>()
            br.add(IrBranch(eq, ret(f, constBool(true)) as IrExpression))
            stmts.add(IrWhen(br, unit, null))
        }
        // if (other !is T) return false
        run {
            val test = IrTypeOperatorCall(IrTypeOperator.NOT_INSTANCEOF, sema.booleanType, getValue(other), self)
            val br = ArrayList<IrBranch>()
            br.add(IrBranch(test, ret(f, constBool(false)) as IrExpression))
            stmts.add(IrWhen(br, unit, null))
        }
        val cast = newTemp("other_with_cast", self, IrTypeOperatorCall(IrTypeOperator.IMPLICIT_CAST, self, getValue(other), self))
        stmts.add(cast)
        for (p in props) {
            val fld = p.backingField!!
            val a = IrGetField(fld, thisOf(f), fld.type)
            val b = IrGetField(fld, getValue(cast), fld.type)
            val name = if (isFloating(fld.type)) "ieee754equals" else "EQEQ"
            val eq = IrCall(builtinFun(name), sema.booleanType)
            eq.origin = "EXCLEQ"
            eq.arguments.add(a)
            eq.arguments.add(b)
            val n = IrCall(memberFunNamed(sema.booleanType, "not", 0), sema.booleanType)
            n.origin = "EXCLEQ"
            n.arguments.add(eq)
            val br = ArrayList<IrBranch>()
            br.add(IrBranch(n, ret(f, constBool(false)) as IrExpression))
            stmts.add(IrWhen(br, unit, null))
        }
        stmts.add(ret(f, constBool(true)))
        IrBlockBody(stmts)
        }
    }
    return f
}

// ---- enums -----------------------------------------------------------------------------------------------------------------

fun Ast2Ir.addEnumMembers(c: IrClass, sym: ClassSym) {
    val arr = sema.varargArrayType(irType(sym.thisType()))
    val arrayType = ClassType(sema.builtin("kotlin.Array"), listOf(TArg(sym.thisType(), Variance.INV)), false)
    val values = findSynth(sym, "values", 0)
    if (values != null) {
        val f = IrSimpleFunction("values")
        f.sym = values
        f.parent = c
        f.origin = "ENUM_CLASS_SPECIAL_MEMBER"
        f.returnType = irType(arrayType)
        funMap[values] = f
        c.declarations.add(f)
    }
    val valueOf = findSynth(sym, "valueOf", 1)
    if (valueOf != null) {
        val f = IrSimpleFunction("valueOf")
        f.sym = valueOf
        f.parent = c
        f.origin = "ENUM_CLASS_SPECIAL_MEMBER"
        f.returnType = irType(sym.thisType())
        val p = IrValueParameter("value", sema.stringType, IrParameterKind.Regular, 0)
        p.parent = f
        f.parameters.add(p)
        funMap[valueOf] = f
        c.declarations.add(f)
    }
    val el = sym.props["entries"]
    if (el != null) {
        for (ep in el) {
            if (!ep.synthetic) continue
            val p = IrProperty("entries", ep)
            p.origin = "ENUM_CLASS_SPECIAL_MEMBER"
            p.parent = c
            val g = IrSimpleFunction("<get-entries>")
            g.origin = "ENUM_CLASS_SPECIAL_MEMBER"
            g.parent = c
            g.correspondingProperty = p
            g.returnType = irType(ep.type ?: sema.nullableAny)
            p.getter = g
            propMap[ep] = p
            c.declarations.add(p)
        }
    }
}
