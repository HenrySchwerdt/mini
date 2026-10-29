@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetEnumValue
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName

/**
 * Prunes the module before it is converted to MIR, so that only the part of the Mini stdlib that
 * the user program can reach ends up in the C output.
 *
 *  1. files marked `@file:kotlin.internal.Builtin` are removed (builtin declarations have no bodies
 *     and are known to the backend by name);
 *  2. top-level functions implemented natively (`external` or `@CSymbol`) are removed; calls to them
 *     are turned into C calls by the backend from the callee symbol alone;
 *  3. in stdlib files only declarations reachable from the user files survive. Roots are all
 *     declarations of the user files. Reachability is by name resolution in the IR: calls,
 *     constructor calls, object/enum references, type operators and every type that occurs in a
 *     declaration or expression. A reachable class keeps all its members (and so everything they
 *     reference); top-level functions are kept individually.
 *
 * This is the only part of the pipeline that knows which files are stdlib files.
 */
object Reachability {
    private val BUILTIN = FqName("kotlin.internal.Builtin")
    private val CSYMBOL = FqName("mini.internal.CSymbol")

    fun isBuiltinFile(f: IrFile): Boolean = f.annotations.any { it.type.classFqName() == BUILTIN }

    fun cSymbol(f: IrFunction): String? {
        val sources = listOfNotNull<org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer>(
            f, (f as? IrSimpleFunction)?.correspondingPropertySymbol?.owner,
        )
        for (s in sources) {
            val a = s.annotations.firstOrNull { it.type.classFqName() == CSYMBOL } ?: continue
            val arg = a.arguments.firstOrNull() as? org.jetbrains.kotlin.ir.expressions.IrConst
            return arg?.value as? String
        }
        return null
    }

    private fun IrType.classFqName(): FqName? =
        ((this as? IrSimpleType)?.classifier?.owner as? IrClass)?.kotlinFqName

    /**
     * @param isStdlibFile true for files of the Mini stdlib sources (they are pruned), false for user files.
     */
    fun prune(module: IrModuleFragment, isStdlibFile: (IrFile) -> Boolean) {
        module.files.removeAll { isBuiltinFile(it) }
        for (f in module.files) {
            f.declarations.removeAll { it is IrFunction && (it.isExternal || cSymbol(it) != null) }
        }
        val marker = Marker()
        for (f in module.files) {
            if (!isStdlibFile(f)) f.acceptChildrenVoid(marker.visitor)
        }
        for (f in module.files) {
            if (isStdlibFile(f)) f.declarations.removeAll { it !in marker.kept }
        }
        module.files.removeAll { isStdlibFile(it) && it.declarations.isEmpty() }
    }

    private class Marker {
        val kept = HashSet<IrDeclaration>()
        private val classes = HashSet<IrClass>()

        val visitor = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                when (element) {
                    is IrExpression -> markType(element.type)
                    is IrValueDeclaration -> markType(element.type)
                    is IrFunction -> markType(element.returnType)
                }
                when (element) {
                    is IrMemberAccessExpression<*> ->
                        // members of builtin-typed receivers (for-loop `hasNext`/`next`, ...) are known to the backend
                        if (!(element.dispatchReceiver?.type.isBuiltinClassType())) markSymbolOwner(element.symbol.owner as? IrDeclaration)
                    is IrGetObjectValue -> markClass(element.symbol.owner)
                    is IrGetEnumValue -> markClass(element.symbol.owner.parentAsClass())
                    is IrClassReference -> markType(element.classType)
                    is IrTypeOperatorCall -> markType(element.typeOperand)
                    is IrFunctionReference -> markSymbolOwner(element.symbol.owner)
                    is IrClass -> { markClass(element); return }
                }
                element.acceptChildrenVoid(this)
            }
        }

        private fun IrType?.isBuiltinClassType(): Boolean {
            val c = (this as? IrSimpleType)?.classifier?.owner as? IrClass ?: return false
            return (c.getPackageFragment() as? IrFile)?.let { isBuiltinFile(it) } == true
        }

        private fun IrDeclaration.parentAsClass(): IrClass = parent as IrClass

        private fun markSymbolOwner(d: IrDeclaration?) {
            when (d) {
                null -> {}
                is IrFunction -> {
                    val p: IrDeclarationParent = d.parent
                    if (p is IrClass) {
                        markClass(p)
                    } else if (p is IrFile) {
                        val top: IrDeclaration = (d as? IrSimpleFunction)?.correspondingPropertySymbol?.owner ?: d
                        if (kept.add(top)) top.acceptVoid(visitor)
                    }
                    if (d is IrSimpleFunction) d.overriddenSymbols.forEach { markSymbolOwner(it.owner) }
                }
                else -> {}
            }
        }

        fun markClass(c: IrClass) {
            if (!classes.add(c)) return
            if ((c.getPackageFragment() as? IrFile)?.let { isBuiltinFile(it) } == true) return
            when (val p = c.parent) {
                is IrClass -> markClass(p)
                is IrFile -> kept.add(c)
                else -> {}
            }
            c.superTypes.forEach { markType(it) }
            c.typeParameters.forEach { tp -> tp.superTypes.forEach { markType(it) } }
            c.acceptChildrenVoid(visitor)
        }

        private fun markType(t: IrType) {
            if (t !is IrSimpleType) return
            (t.classifier.owner as? IrClass)?.let { markClass(it) }
            for (a in t.arguments) if (a is IrTypeProjection) markType(a.type)
        }
    }
}
