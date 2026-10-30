package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/**
 * True for `fun main(args: Array<String>)`: the program entry with the command line (bootstrap B1).
 * The C entry wrapper obtains the array from the runtime (`mk_b1_args`).
 */
internal fun isArgsMain(f: IrFunction): Boolean {
    val ps = f.parameters
    if (ps.size != 1) return false
    val t = ps[0].type as? ClassType ?: return false
    if (t.classFqName != "kotlin.Array") return false
    val arg = t.args.singleOrNull()?.type ?: return false
    return arg.classFqName == "kotlin.String"
}
