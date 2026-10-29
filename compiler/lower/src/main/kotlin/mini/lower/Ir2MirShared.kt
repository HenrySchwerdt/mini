
package mini.lower

import mini.backend.*
import mini.frontend.sema.*


/* Types and constants shared by the Ir2Mir files. */

internal class ClassInfo(val ir: IrClass, val cName: String, val qualified: String, val file: IrFile?)

internal sealed class Dispatch {
    class AnyMember(val slot: Int) : Dispatch()
    class Virtual(val slot: Int) : Dispatch()
    class Interface(val cName: String, val index: Int) : Dispatch()
    class Direct(val target: IrSimpleFunction) : Dispatch()
}

internal class Progression(
    val start: IrExpression,
    val end: IrExpression,
    val step: IrExpression?,
    val ascending: Boolean,
    val inclusive: Boolean,
)

internal val RANGES = setOf("kotlin.ranges.IntRange", "kotlin.ranges.LongRange", "kotlin.ranges.CharRange")
internal val PRIMITIVES = setOf("kotlin.Int", "kotlin.Long", "kotlin.Double", "kotlin.Boolean", "kotlin.Char", "kotlin.String")

