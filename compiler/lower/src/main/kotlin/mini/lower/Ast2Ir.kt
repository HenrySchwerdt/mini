package mini.lower

import mini.frontend.sema.*

/** Builds the IR of a set of analysed sources (the equivalent of K2's fir2ir). */
class Ast2Ir(val sema: Sema) {
    fun irClass(sym: ClassSym): IrClass = throw IllegalStateException("todo")

    fun functionClass(arity: Int): IrClass = throw IllegalStateException("todo")
}
