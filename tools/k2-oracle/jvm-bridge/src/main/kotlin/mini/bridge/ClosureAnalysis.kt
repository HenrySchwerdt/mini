@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Which variables does a lambda / local function / local class capture from enclosing functions?
 *
 * Result:
 *  - [captures]: for every closure-like declaration (a lambda or local function that is really
 *    compiled as a closure, or a local class) the outer values it uses, in first-use order;
 *  - [boxed]: `var`s that are captured by a closure; they live in a heap cell (Ref) so that the
 *    closure and the declaring function share them. `val`s are captured by value.
 *
 * Lambdas passed to `inline` functions (not noinline/crossinline) are expanded in place by the
 * bridge and therefore are transparent here: they neither capture nor force boxing.
 */
internal class ClosureInfo {
    val captures = HashMap<IrDeclaration, LinkedHashSet<IrValueDeclaration>>()
    val boxed = HashSet<IrValueDeclaration>()
    private val owner = HashMap<IrValueDeclaration, IrDeclaration?>()
    private val calls = ArrayList<Pair<List<IrDeclaration>, IrDeclaration>>()

    /** Local and anonymous classes (found in function bodies), in source order. */
    class LocalClass(val cls: IrClass, val file: IrFile, val enclosing: String)
    val localClasses = ArrayList<LocalClass>()
    private var currentFile: IrFile? = null

    /** Lambda literals the bridge expands in place (arguments of inline functions). */
    val inlinedLambdas = HashSet<IrFunction>()

    fun capturesOf(d: IrDeclaration): List<IrValueDeclaration> = captures[d]?.toList() ?: emptyList()

    fun analyze(module: IrModuleFragment) {
        val v = Visitor()
        for (f in module.files) {
            currentFile = f
            f.acceptChildrenVoid(v)
        }
        // a call of a local function uses everything the callee captures
        var changed = true
        while (changed) {
            changed = false
            for ((stack, callee) in calls) {
                for (x in captures[callee]?.toList() ?: continue) changed = changed or use(stack, x)
            }
        }
    }

    /**
     * The member function (of the class [v] is the `this` of, or of an inner class nested in it) in which
     * the outer `this` [v] is available; used for `this@Outer` inside inner classes.
     */
    private fun outerThisOwner(stack: List<IrDeclaration>, v: IrValueDeclaration): IrDeclaration? {
        val cls = (v as? IrValueParameter)?.parent as? IrClass ?: return null
        if (cls.thisReceiver !== v) return null
        for (i in stack.indices.reversed()) {
            val d = stack[i]
            if (d !is IrFunction) continue
            var k = d.parent as? IrClass
            while (k != null) {
                if (k === cls) return d
                k = if (k.isInner) k.parent as? IrClass else null
            }
        }
        return null
    }

    private fun use(stack: List<IrDeclaration>, v: IrValueDeclaration): Boolean {
        val d = owner[v] ?: outerThisOwner(stack, v) ?: return false
        val idx = stack.lastIndexOf(d)
        if (idx < 0) return false
        var changed = false
        for (i in idx + 1 until stack.size) {
            if (captures.getOrPut(stack[i]) { LinkedHashSet() }.add(v)) changed = true
            if (v is IrVariable && v.isVar) boxed += v
        }
        return changed
    }

    private inner class Visitor : IrVisitorVoid() {
        /** Enclosing declarations that are closure boundaries (functions and classes), outermost first. */
        val stack = ArrayList<IrDeclaration>()

        /** The function in which a newly declared value lives (transparent lambdas do not count). */
        var fnOwner: IrDeclaration? = null

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunctionExpression(expression: IrFunctionExpression) {
            // transparent inline lambdas are registered by visitCall before descending
            expression.acceptChildrenVoid(this)
        }

        override fun visitClass(declaration: IrClass) {
            if (declaration.visibility == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.LOCAL) {
                val fnName = (stack.lastOrNull { it is IrFunction } as? IrFunction)?.name?.asString() ?: "init"
                localClasses += LocalClass(declaration, currentFile!!, fnName)
            }
            val savedOwner = fnOwner
            stack += declaration
            // members of a class see the class as their closure boundary; their values belong to them
            declaration.acceptChildrenVoid(this)
            stack.removeAt(stack.size - 1)
            fnOwner = savedOwner
        }

        override fun visitFunction(declaration: IrFunction) {
            val transparent = declaration in inlinedLambdas
            val savedOwner = fnOwner
            if (!transparent) {
                stack += declaration
                fnOwner = declaration
            }
            for (p in declaration.parameters) owner[p] = fnOwner
            declaration.acceptChildrenVoid(this)
            if (!transparent) stack.removeAt(stack.size - 1)
            fnOwner = savedOwner
        }

        override fun visitVariable(declaration: IrVariable) {
            owner[declaration] = fnOwner
            declaration.acceptChildrenVoid(this)
        }

        override fun visitCall(expression: IrCall) {
            val callee = expression.symbol.owner
            if (callee.isInline) {
                for ((i, a) in expression.arguments.withIndex()) {
                    val p = callee.parameters.getOrNull(i) ?: continue
                    if (a is IrFunctionExpression && !p.isNoinline && !p.isCrossinline) inlinedLambdas += a.function
                }
            }
            if (callee.origin == IrDeclarationOrigin.LOCAL_FUNCTION || callee.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA ||
                callee.visibility == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.LOCAL
            ) {
                calls += ArrayList(stack) to callee
            }
            expression.acceptChildrenVoid(this)
        }

        private fun constructorEdge(callee: IrDeclaration?) {
            val cls = (callee as? IrConstructor)?.parent as? IrClass ?: return
            if (cls.visibility == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.LOCAL) calls += ArrayList(stack) to cls
        }

        override fun visitConstructorCall(expression: IrConstructorCall) {
            constructorEdge(expression.symbol.owner)
            expression.acceptChildrenVoid(this)
        }

        override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall) {
            constructorEdge(expression.symbol.owner)
            expression.acceptChildrenVoid(this)
        }

        override fun visitGetValue(expression: IrGetValue) {
            use(stack, expression.symbol.owner)
        }

        override fun visitSetValue(expression: IrSetValue) {
            use(stack, expression.symbol.owner)
            expression.acceptChildrenVoid(this)
        }
    }
}
