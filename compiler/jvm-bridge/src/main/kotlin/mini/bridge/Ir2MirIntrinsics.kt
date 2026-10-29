@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.MirExpr
import mini.backend.MirStmt
import org.jetbrains.kotlin.ir.expressions.IrCall

/**
 * Calls of natively implemented functions (`@mini.internal.CSymbol("mk_x")`, see docs/stdlib.md):
 * a plain C call of the symbol with the receiver (if any) first, then the arguments; arguments are
 * borrowed, a reference result is owned.
 */
internal fun Ir2Mir.cSymbolCall(c: IrCall, symbol: String, out: MutableList<MirStmt>): MirExpr {
    val args = c.arguments.map { it ?: unsupported(c, "call with omitted argument to '$symbol'") }
    return MirExpr.RuntimeCall(symbol, operands(args, out), mirType(c.type, c))
}
