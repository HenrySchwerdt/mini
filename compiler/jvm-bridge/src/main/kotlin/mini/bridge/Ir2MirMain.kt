package mini.bridge

import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName

/**
 * True for `fun main(args: Array<String>)`: the program entry with the command line (bootstrap B1).
 * The C entry wrapper obtains the array from the runtime (`mk_b1_args`).
 */
internal fun isArgsMain(f: IrFunction): Boolean {
    val ps = f.parameters
    if (ps.size != 1) return false
    val t = ps[0].type as? IrSimpleType ?: return false
    if (t.classFqName?.asString() != "kotlin.Array") return false
    val arg = t.arguments.singleOrNull() as? IrTypeProjection ?: return false
    return arg.type.classFqName?.asString() == "kotlin.String"
}
