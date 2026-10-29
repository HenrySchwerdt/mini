package mini.frontend.sema

import mini.frontend.*

/*
 * B2-3: details of the resolution that the lowering to IR needs and that the type / callee oracle does not look at:
 * which receivers a call or a property access uses (explicit expression, implicit `this` of an enclosing class, object,
 * receiver of an extension function or lambda), how `invoke` conventions were resolved.
 */

/** Fills the receivers of [info] after the call `callee(args)` was resolved with the receiver part [recv]. */
fun BodyChecker.fillReceivers(info: CallInfo, recv: RecvInfo?, callee: Expr) {
    val cand = info.cand ?: return
    val fn = info.fn
    val isInvoke = fn.name == "invoke" && !(callee is NameExpr && callee.name == "invoke") &&
        (fn.kind == FunKind.FUNCTION_INVOKE || cand.invokeVar != null || cand.invokeProp != null || callee !is NameExpr || cand.dispatchRef != null)
    if (isInvoke) {
        info.isInvoke = true
        val v = cand.invokeVar
        val p = cand.invokeProp
        if (v != null) {
            info.invokeVar = v
        } else if (p != null) {
            info.invokeProp = p
            val pr = cand.invokePropRecv
            if (pr != null) info.dispatch = RecvUse(null, false, pr, null)
            else if (recv is ValueRecv) info.dispatch = RecvUse(recv.expr, recv.safe, null, recv.type)
        } else if (cand.dispatchRef != null && callee is NameExpr) {
            info.dispatch = RecvUse(null, false, cand.dispatchRef, null)
        } else if (callee !is NameExpr) {
            info.invokeValue = RecvUse(callee, false, null, null)
        } else if (recv is ValueRecv) {
            info.invokeValue = RecvUse(recv.expr, recv.safe, null, recv.type)
        }
        return
    }
    val dref = cand.dispatchRef
    val eref = cand.extRef
    if (recv is ValueRecv) {
        if (fn.receiver == null && fn.kind != FunKind.CONSTRUCTOR) {
            info.dispatch = RecvUse(recv.expr, recv.safe, null, recv.type)
        } else if (fn.kind == FunKind.CONSTRUCTOR) {
            // inner class constructor `outer.Inner()`: the outer instance
            info.dispatch = RecvUse(recv.expr, recv.safe, null, recv.type)
        } else {
            info.ext = RecvUse(recv.expr, recv.safe, null, recv.type)
            if (dref != null) info.dispatch = RecvUse(null, false, dref, null)
        }
        return
    }
    if (dref != null) info.dispatch = RecvUse(null, false, dref, null)
    if (eref != null) info.ext = RecvUse(null, false, eref, null)
    if (recv is ClassRecv && dref == null) {
        val o = fn.owner
        if (o != null && (o.isObject || o.isCompanion) && fn.kind != FunKind.CONSTRUCTOR) info.dispatch = RecvUse(null, false, RecvRef(o, null, true), null)
    }
}
