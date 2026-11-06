package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/*
 * Ir2Mir: value classes (`value class Meters(val v: Int)`, the unsigned types).
 *
 * A value class over a primitive with no secondary constructors is *unboxed*: its values have the MIR type [TInline], which is
 * represented exactly like the underlying primitive. Members are ordinary functions whose `this` is that value. The class itself
 * still exists as a heap class with one field, the *boxed* form: it is what `Any`, generics, interfaces and `Foo?` hold
 * (`TBoxed(TInline)`). Two small functions convert, `<cName>_box` and `<cName>_unbox`; the vtable / itable entries of such a
 * class are wrappers (`<member>_vb`) that unbox `this` first. Value classes over references (String, objects, generics) are
 * ordinary boxed classes.
 */

/** The representation of the unboxed value class [c], or null when [c] is not (or not unboxable). */
internal fun Ir2Mir.inlineRepOf(c: IrClass): MirType? {
    if (!c.isValue) return null
    if (inlineReps.containsKey(c)) return inlineReps[c]
    inlineReps[c] = null // recursion guard
    var rep: MirType? = null
    if (c.kind == IrClassKind.CLASS && c in classInfos && c.typeParameters.isEmpty() && !c.isInner) {
        val ctors = c.declarations.filterIsInstance<IrConstructor>()
        val fields = ownFields(c)
        if (ctors.size == 1 && fields.size == 1) {
            val ft = mirType(fields[0].type, fields[0])
            if (ft.isPrimitive) rep = ft
        }
    }
    inlineReps[c] = rep
    return rep
}

internal fun Ir2Mir.inlineTypeOf(c: IrClass): TInline? {
    val rep = inlineRepOf(c) ?: return null
    val i = info(c)
    return TInline(i.qualified, i.cName, rep)
}

/** Dispatch of a call on [f]: a member of an unboxed value class (a final class) is always called directly. */
internal fun Ir2Mir.dispatchOfValue(f: IrSimpleFunction): Dispatch {
    val d = dispatchOf(f)
    val p = f.parent as? IrClass
    if ((d is Dispatch.Virtual || d is Dispatch.Interface) && p != null && inlineRepOf(p) != null) return Dispatch.Direct(resolveFake(f))
    return d
}

/** The class of an unboxed value class type. */
internal fun Ir2Mir.inlineClassOf(t: TInline): IrClass? = classInfos.entries.firstOrNull { it.value.cName == t.cName }?.key

internal fun inlineObjType(t: TInline, nullable: Boolean) = TObject(t.qualifiedName, t.cName, nullable)

/** Retypes [e] (same representation) as [to]. */
internal fun retag(e: MirExpr, to: MirType): MirExpr = if (e.type == to) e else MirExpr.Convert(e, to)

/** A value of an unboxed value class as the heap object (an owned reference). */
internal fun Ir2Mir.boxVc(e: MirExpr, asType: MirType? = null): MirExpr {
    val t = e.type as TInline
    return MirExpr.Call(t.cName + "_box", listOf(e), asType ?: TBoxed(t))
}

/** The unboxed value of the boxed value class object [e] (a [TBoxed], `Any` or the class type). */
internal fun Ir2Mir.unboxVc(e: MirExpr, t: TInline, mode: MirExpr.CastMode): MirExpr {
    val obj = inlineObjType(t, true)
    val arg = if (e.type == obj) e else MirExpr.Cast(e, obj, mode)
    return MirExpr.Call(t.cName + "_unbox", listOf(arg), t)
}

/** `Unbox(Cast(v, TBoxed(t), mode), t)` for every primitive [t], value classes included. */
internal fun Ir2Mir.unboxCast(v: MirExpr, t: MirType, mode: MirExpr.CastMode): MirExpr =
    // (the unbox of a primitive checks the box type and null itself: a checked cast in front of it would test the same twice)
    if (t is TInline) unboxVc(v, t, mode) else MirExpr.Unbox(MirExpr.Cast(v, TBoxed(t), MirExpr.CastMode.Unchecked), t)

/** The box and unbox functions and the boxed constructor of an unboxed value class. */
internal fun Ir2Mir.valueClassFunctions(info: ClassInfo): List<MirFunction> {
    val c = info.ir
    val t = inlineTypeOf(c) ?: return emptyList()
    val field = ownFields(c)[0]
    val ft = mirType(field.type, field)
    val obj = inlineObjType(t, false)
    val out = ArrayList<MirFunction>()
    // the boxed constructor: only stores the field (init blocks ran when the unboxed value was made)
    val initName = info.cName + "_boxinit"
    val self = MirLocal(0, "this", obj)
    val xi = MirLocal(1, "x", t)
    out += MirFunction(
        info.qualified + ".<box>", initName, listOf(self, xi), TUnit,
        listOf(MirStmt.SetField(MirExpr.Local(self), info.cName, field.name, ft, retag(MirExpr.Local(xi), ft))), info.cName,
    )
    val x = MirLocal(0, "x", t)
    out += MirFunction(
        info.qualified + ".\$box", info.cName + "_box", listOf(x), TBoxed(t),
        listOf(MirStmt.Return(MirExpr.New(initName, listOf(MirExpr.Local(x)), obj))),
    )
    val o = MirLocal(0, "o", inlineObjType(t, true))
    out += MirFunction(
        info.qualified + ".\$unbox", info.cName + "_unbox", listOf(o), t,
        listOf(MirStmt.Return(retag(MirExpr.FieldGet(MirExpr.NotNull(MirExpr.Local(o)), info.cName, field.name, ft), t))),
    )
    // creating a value runs the init blocks
    if (c.declarations.any { it is IrAnonymousInitializer }) out += valueClassMake(info, t)
    return out
}

private fun Ir2Mir.valueClassMake(info: ClassInfo, t: TInline): MirFunction = inFreshFunction {
    val c = info.ir
    val k = c.declarations.filterIsInstance<IrConstructor>()[0]
    val x = MirLocal(localCounter++, "x", t)
    val self = MirLocal(localCounter++, "this", t)
    locals[k.parameters.first { it.kind == IrParameterKind.Regular }] = x
    c.thisReceiver?.let { locals[it] = self }
    currentFunction = k
    currentReturn = t
    unitAsNull = false
    finallyStack = ArrayList()
    val out = ArrayList<MirStmt>()
    out += MirStmt.Declare(self, MirExpr.Local(x))
    for (d in c.declarations) if (d is IrAnonymousInitializer) for (s in d.body.statements) stmt(s, out)
    out += MirStmt.Return(MirExpr.Local(self))
    MirFunction(info.qualified + ".\$make", info.cName + "_make", listOf(x), t, out)
}

/** The unboxed value of `Foo(args)` for an unboxed value class. */
internal fun Ir2Mir.valueClassNew(c: IrConstructorCall, k: IrConstructor, cls: IrClass, out: MutableList<MirStmt>): MirExpr {
    val t = inlineTypeOf(cls)!!
    val args = callArgs(c, k, out)
    val a = args[0]
    if (cls.declarations.none { it is IrAnonymousInitializer }) return retag(coerceFlexible(a, t.rep, c, out), t)
    return MirExpr.Call(t.cName + "_make", listOf(retag(coerceFlexible(a, t.rep, c, out), t)), t)
}

/**
 * The function to put into a vtable / itable slot for [f], a member of an unboxed value class: it takes the boxed object as
 * `this` and calls [name] with the unboxed value.
 */
internal fun Ir2Mir.valueClassWrapper(f: IrSimpleFunction, name: String): String {
    val c = f.parent as IrClass
    val t = inlineTypeOf(c) ?: return name
    val wname = name + "_vb"
    if (!bridgeCache.add(wname)) return wname
    val failures = libFailures
    val fn = try {
        inFreshFunction {
            val ps = f.parameters.mapIndexed { i, p ->
                if (i == 0) MirLocal(localCounter++, "this", inlineObjType(t, true))
                else MirLocal(localCounter++, "p$i", mirType(p.type, p))
            }
            val rt = mirType(f.returnType, f)
            val args = ps.mapIndexed { i, p -> if (i == 0) unboxVc(MirExpr.Local(p), t, MirExpr.CastMode.Unchecked) else MirExpr.Local(p) }
            val out = ArrayList<MirStmt>()
            val call = MirExpr.Call(name, args, rt)
            if (rt == TUnit || rt == TNothing) {
                out += MirStmt.Eval(call)
                out += MirStmt.Return(null)
            } else {
                out += MirStmt.Return(call)
            }
            MirFunction(f.name + "\$vb", wname, ps, rt, out)
        }
    } catch (e: UnsupportedConstruct) {
        if (failures == null) throw e
        failures[wname] = (e.message ?: "unsupported construct").replace('\n', ' ')
        MirFunction(f.name + "\$vb", wname, f.parameters.mapIndexed { i, p -> MirLocal(i, if (i == 0) "this" else "p$i", if (i == 0) inlineObjType(t, true) else mirType(p.type, p)) }, mirType(f.returnType, f), listOf(MirStmt.Panic("unsupported construct in library code")))
    }
    extraFunctions += fn
    return wname
}

/**
 * `toString`, `equals` or `hashCode` (Any slot [slot]) of the unboxed value [recv] called directly, without boxing: the member of its
 * class that overrides the Any member.
 */
internal fun Ir2Mir.valueClassAnyMember(recv: MirExpr, slot: Int, rest: List<MirExpr>, at: IrElement): MirExpr? {
    val t = recv.type as? TInline ?: return null
    val cls = inlineClassOf(t) ?: return null
    val g = classFunctions(cls).firstOrNull { !it.isFakeOverride && it.parameters.size == 1 + rest.size && topClass(it)?.let { top -> anyMember(top) } == slot }
        ?: return null
    val name = functionNames[g] ?: return null
    val rt = mirType(g.returnType, g)
    return MirExpr.Call(name, listOf(recv) + rest, rt)
}

/** Does the value class [t] use the generated `equals` (field comparison), not a user-defined one? */
internal fun Ir2Mir.hasSyntheticEquals(t: TInline): Boolean {
    val cls = inlineClassOf(t) ?: return false
    val eq = classFunctions(cls).firstOrNull { it.name == "equals" && it.parameters.size == 2 } ?: return false
    return eq.origin == "GENERATED_DATA_CLASS_MEMBER"
}
