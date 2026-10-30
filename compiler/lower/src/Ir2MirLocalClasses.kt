
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/*
 * Ir2Mir: inner, local and anonymous classes.
 *
 *  - A local / anonymous class captures the outer values it uses (ClosureInfo.captures of the class):
 *    they are hidden fields `cap<i>` (the Ref cell for captured `var`s), passed as extra trailing
 *    constructor arguments and copied into locals at the start of every member (like a lambda).
 *  - An inner class has a hidden field `$outer` (its constructor takes the outer instance as first
 *    parameter); `this@Outer` inside its members reads that field.
 */

internal fun Ir2Mir.classCaptures(c: IrClass): List<IrValueDeclaration> = closure.capturesOf(c)

internal fun Ir2Mir.captureTypesFor(c: IrClass): List<MirType> = classCaptures(c).map { capturedType(it) }

/** The captured values of [cls] as seen from the current function (arguments of its constructor). */
internal fun Ir2Mir.captureArgsFor(cls: IrClass, at: IrElement): List<MirExpr> =
    classCaptures(cls).map { MirExpr.Local(localOf(it, at)) }

/**
 * Called at the start of the conversion of member [f] of the user class [cls]: binds captured outer
 * values and the outer instance as locals, and (in constructors) stores them into the hidden fields.
 */
internal fun Ir2Mir.enterClassMember(f: IrFunction, cls: IrClass, params: MutableList<MirLocal>, out: MutableList<MirStmt>) {
    val caps = classCaptures(cls)
    val info = info(cls)
    if (f is IrConstructor) {
        val self = params[0]
        val capLocals = caps.mapIndexed { i, v -> MirLocal(localCounter++, "cap$i", capturedType(v)) }
        params += capLocals
        for ((i, v) in caps.withIndex()) {
            out += MirStmt.SetField(MirExpr.Local(self), info.cName, "cap$i", capLocals[i].type, MirExpr.Local(capLocals[i]))
            locals[v] = capLocals[i]
        }
        if (cls.isInner) {
            val outerCls = cls.parent as IrClass
            val outerParam = params.getOrNull(if (cls.kind == IrClassKind.ENUM_CLASS) 3 else 1) ?: return
            out += MirStmt.SetField(MirExpr.Local(self), info.cName, "\$outer", typeOf(outerCls), MirExpr.Local(outerParam))
            outerCls.thisReceiver?.let { locals[it] = outerParam }
        }
        return
    }
    if (f.parameters.firstOrNull()?.kind != IrParameterKind.DispatchReceiver) return
    val self = params[0]
    for ((i, v) in caps.withIndex()) {
        val l = MirLocal(localCounter++, "cap$i", capturedType(v))
        out += MirStmt.Declare(l, MirExpr.FieldGet(MirExpr.Local(self), info.cName, "cap$i", l.type))
        locals[v] = l
    }
    var cur: MirLocal = self
    var k = cls
    while (k.isInner) {
        val outerCls = k.parent as? IrClass ?: break
        val l = MirLocal(localCounter++, "outer", typeOf(outerCls))
        out += MirStmt.Declare(l, MirExpr.FieldGet(MirExpr.Local(cur), info(k).cName, "\$outer", l.type))
        outerCls.thisReceiver?.let { locals[it] = l }
        cur = l
        k = outerCls
    }
}
