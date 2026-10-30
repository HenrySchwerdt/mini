
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/**
 * Calls of natively implemented functions (`@mini.internal.CSymbol("mk_x")`, see docs/stdlib.md):
 * a plain C call of the symbol with the receiver (if any) first, then the arguments; arguments are
 * borrowed, a reference result is owned.
 */
internal fun Ir2Mir.cSymbolCall(c: IrCall, symbol: String, out: MutableList<MirStmt>): MirExpr {
    val args = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$symbol'") }
    val type = mirType(c.type, c)
    val call = MirExpr.RuntimeCall(symbol, operands(args, out), type)
    if (type == TNothing) {
        // a call that never returns (`_Noreturn`): it must still be emitted, as a statement
        out += MirStmt.Eval(call)
        return MirExpr.Unreachable(TNothing)
    }
    return call
}
