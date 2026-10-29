package mini.lower

import mini.frontend.sema.*

/**
 * Prunes the module before it is converted to MIR, so that only the part of the Mini stdlib that
 * the user program can reach ends up in the C output (port of jvm-bridge/Reachability, same rules).
 *
 *  1. files marked `@file:kotlin.internal.Builtin` are removed (builtin declarations have no bodies
 *     and are known to the backend by name);
 *  2. top-level functions implemented natively (`external` or `@CSymbol`) are removed; calls to them
 *     are turned into C calls by the backend from the callee alone;
 *  3. in stdlib files only declarations reachable from the user files survive. Roots are all
 *     declarations of the user files. Reachability is by name resolution in the IR: calls,
 *     constructor calls, object/enum references, type operators and every type that occurs in a
 *     declaration or expression. A reachable class keeps all its members (and so everything they
 *     reference); top-level functions are kept individually.
 */
object Reachability {
    fun isBuiltinFile(f: IrFile): Boolean = f.isBuiltin

    fun cSymbol(f: IrFunction): String? {
        val own = f.cSymbolName
        if (own != null) return own
        if (f is IrSimpleFunction) {
            val p = f.correspondingProperty
            if (p != null) return p.cSymbolName
        }
        return null
    }

    fun fileOf(d: IrDeclaration): IrFile? {
        var p: IrDeclarationParent? = d.parent
        while (p != null) {
            if (p is IrFile) return p
            if (p is IrDeclaration) p = p.parent else return null
        }
        return null
    }

    fun prune(module: IrModule) {
        val marker = Marker()
        for (f in module.files) {
            for (d in f.declarations) {
                if (d is IrClass && d.kotlinFqName == REFLECT_PKG + ".KClassImpl") {
                    marker.kclassImpl = d
                    break
                }
            }
            if (marker.kclassImpl != null) break
        }
        val keepFiles = ArrayList<IrFile>()
        for (f in module.files) if (!isBuiltinFile(f)) keepFiles.add(f)
        module.files.clear()
        module.files.addAll(keepFiles)
        for (f in module.files) {
            val keep = ArrayList<IrDeclaration>()
            for (d in f.declarations) {
                if (d is IrFunction && (d.isExternal || cSymbol(d) != null)) continue
                keep.add(d)
            }
            f.declarations.clear()
            f.declarations.addAll(keep)
        }
        for (f in module.files) {
            if (!f.isStdlib) marker.visitChildren(f)
        }
        for (f in module.files) {
            if (f.isStdlib) {
                val keep = ArrayList<IrDeclaration>()
                for (d in f.declarations) if (marker.kept.contains(d)) keep.add(d)
                f.declarations.clear()
                f.declarations.addAll(keep)
                for (d in f.declarations) if (d is IrClass) marker.pruneMembers(d)
            }
        }
        val rest = ArrayList<IrFile>()
        for (f in module.files) if (!(f.isStdlib && f.declarations.isEmpty())) rest.add(f)
        module.files.clear()
        module.files.addAll(rest)
    }

    private class Marker : IrWalker() {
        val kept = HashSet<IrDeclaration>()
        /** `Foo::class` / `x::class` create a KClassImpl (Ir2MirExpressions.classReference). */
        var kclassImpl: IrClass? = null
        private val classes = HashSet<IrClass>()

        private fun isStdlibClass(c: IrClass): Boolean {
            val f = fileOf(c)
            return f != null && f.isStdlib
        }

        /**
         * A plain member function of a stdlib class that nothing can reach by dispatch: final, not an override,
         * not an accessor, not in an interface. It is only kept (and its body only walked) when called.
         */
        private fun isDemandDriven(f: IrDeclaration, c: IrClass): Boolean =
            f is IrSimpleFunction && isStdlibClass(c) && c.kind != IrClassKind.INTERFACE && !f.isFakeOverride &&
                f.overridden.isEmpty() && f.correspondingProperty == null && f.modality == IrModality.FINAL

        /** Drops demand-driven member functions that were never called (nested classes are handled recursively). */
        fun pruneMembers(c: IrClass) {
            if (!classes.contains(c)) return
            val keep = ArrayList<IrDeclaration>()
            for (d in c.declarations) if (!(isDemandDriven(d, c) && !kept.contains(d))) keep.add(d)
            c.declarations.clear()
            c.declarations.addAll(keep)
            for (d in c.declarations) if (d is IrClass) pruneMembers(d)
        }

        override fun visit(e: IrElement) {
            if (e is IrExpression) markType(e.type)
            if (e is IrValueDeclaration) markType(e.type)
            if (e is IrFunction) markType(e.returnType)
            when (e) {
                is IrFunctionAccessExpression -> {
                    // members of builtin-typed receivers (for-loop `hasNext`/`next`, ...) are known to the backend
                    val callee = calleeOf(e)
                    val dispatch = dispatchReceiverTypeOf(e, callee)
                    if (!(dispatch != null && isBuiltinClassType(dispatch))) markSymbolOwner(callee)
                    if (e is IrFunctionReference) markSymbolOwner(e.target)
                }
                is IrGetObjectValue -> markClass(e.classOwner)
                is IrGetEnumValue -> markClass(e.entry.parent as IrClass)
                is IrClassReference -> {
                    markType(e.classType)
                    val k = kclassImpl
                    if (k != null) markClass(k)
                }
                is IrGetClass -> {
                    val k = kclassImpl
                    if (k != null) markClass(k)
                }
                is IrTypeOperatorCall -> markType(e.typeOperand)
                is IrClass -> {
                    markClass(e)
                    return
                }
                else -> {}
            }
            visitChildren(e)
        }

        private fun calleeOf(e: IrFunctionAccessExpression): IrFunction? = when (e) {
            is IrCall -> e.callee
            is IrConstructorCall -> e.callee
            is IrDelegatingConstructorCall -> e.callee
            is IrEnumConstructorCall -> e.callee
            is IrFunctionReference -> e.target
            else -> null
        }

        /** The type of the dispatch receiver argument of a call (the first argument of a member function). */
        private fun dispatchReceiverTypeOf(e: IrFunctionAccessExpression, callee: IrFunction?): Type? {
            if (callee == null || callee.parameters.isEmpty() || callee.parameters[0].kind != IrParameterKind.DispatchReceiver) return null
            val a = if (e.arguments.isNotEmpty()) e.arguments[0] else null
            return a?.type
        }

        private fun isBuiltinClassType(t: Type): Boolean {
            val c = t.classOrNull ?: return false
            val f = fileOf(c) ?: return false
            return isBuiltinFile(f)
        }

        private fun markSymbolOwner(d: IrDeclaration?) {
            when (d) {
                null -> {}
                is IrFunction -> {
                    val p = d.parent
                    if (p is IrClass) {
                        markClass(p)
                        if (isDemandDriven(d, p) && kept.add(d)) visit(d)
                    } else if (p is IrFile) {
                        var top: IrDeclaration = d
                        if (d is IrSimpleFunction) {
                            val cp = d.correspondingProperty
                            if (cp != null) top = cp
                        }
                        if (kept.add(top)) visit(top)
                    }
                    if (d is IrSimpleFunction) for (o in d.overridden) markSymbolOwner(o)
                }
                else -> {}
            }
        }

        fun markClass(c: IrClass) {
            if (!classes.add(c)) return
            val cf = fileOf(c)
            if (cf != null && isBuiltinFile(cf)) return
            val p = c.parent
            if (p is IrClass) markClass(p) else if (p is IrFile) kept.add(c)
            for (t in c.superTypes) markType(t)
            for (tp in c.typeParameters) for (t in tp.bounds) markType(t)
            val ds = ArrayList<IrDeclaration>(c.declarations)
            for (d in ds) {
                if (isDemandDriven(d, c)) continue
                visit(d)
            }
        }

        private fun markType(t: Type) {
            when (t) {
                is ClassType -> {
                    val c = t.classOrNull
                    if (c != null) markClass(c)
                    for (a in t.args) {
                        val x = a.type
                        if (x != null) markType(x)
                    }
                }
                is FunType -> {
                    val r = t.receiver
                    if (r != null) markType(r)
                    for (p in t.params) markType(p)
                    markType(t.ret)
                }
                else -> {}
            }
        }
    }
}
