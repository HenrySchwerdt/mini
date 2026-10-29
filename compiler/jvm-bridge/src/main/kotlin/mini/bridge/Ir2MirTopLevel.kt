@file:OptIn(org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI::class)

package mini.bridge

import mini.backend.*
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.util.kotlinFqName

/*
 * Ir2Mir: top-level properties.
 *
 * The properties of a file live in the fields of a hidden singleton, the file facade
 * (`<File>Kt`, like the JVM class), which is an ordinary MIR `object`. Accessors are compiled as
 * ordinary top-level functions; a static field access is a field access on the facade singleton.
 * The facade is created on first access (as every object) and additionally forced at the start of
 * `main`, so initialisers run eagerly, in textual order, before `main` — as on the JVM.
 */

internal class Facade(val cName: String, val qualified: String, val file: IrFile, val fields: List<IrField>, val ctorName: String) {
    val type = TObject(qualified, cName, false)
}

internal fun Ir2Mir.facadeFor(file: IrFile): Facade = facades.getOrPut(file) {
    val base = file.name.removeSuffix(".kt").replaceFirstChar { it.uppercase() } + "Kt"
    val pkg = file.packageFqName.asString()
    val cName = Mangling.className(pkg, listOf(base))
    val fields = file.declarations.filterIsInstance<IrProperty>().filter { it.getter?.body != null || it.getter == null }.mapNotNull { it.backingField } +
        file.declarations.filterIsInstance<IrField>()
    Facade(cName, (if (pkg.isEmpty()) "" else "$pkg.") + base, file, fields, Mangling.constructor(cName, emptyList()))
}

internal fun Ir2Mir.facadeOfField(f: IrField, at: IrElement): Facade {
    val file = f.parent as? IrFile ?: unsupported(at, "static field '${f.name}'")
    return facadeFor(file)
}

internal fun Ir2Mir.staticFieldGet(f: IrField, at: IrElement): MirExpr {
    val fa = facadeOfField(f, at)
    return MirExpr.FieldGet(MirExpr.ObjectGet(fa.cName, fa.type), fa.cName, f.name.asString(), mirType(f.type, f))
}

internal fun Ir2Mir.staticFieldSet(f: IrField, value: IrExpression, at: IrElement, out: MutableList<MirStmt>) {
    val fa = facadeOfField(f, at)
    val ft = mirType(f.type, f)
    val v = bindValue(value, ft, out)
    out += MirStmt.SetField(MirExpr.ObjectGet(fa.cName, fa.type), fa.cName, f.name.asString(), ft, v)
}

/** The class and initialiser of every file facade, built once the other functions are converted. */
internal fun Ir2Mir.buildFacades(): Pair<List<MirClass>, List<MirFunction>> {
    val classes = ArrayList<MirClass>()
    val functions = ArrayList<MirFunction>()
    for (fa in facades.values.toList()) {
        setFile(fa.file)
        val fn = inFreshFunction {
            val self = MirLocal(localCounter++, "this", fa.type)
            val out = ArrayList<MirStmt>()
            for (f in fa.fields) {
                val e = f.initializer?.expression ?: continue
                val ft = mirType(f.type, f)
                out += MirStmt.SetField(MirExpr.Local(self), fa.cName, f.name.asString(), ft, bindValue(e, ft, out))
            }
            MirFunction(fa.qualified + ".<clinit>", fa.ctorName, listOf(self), TUnit, out, fa.cName)
        }
        functions += fn
        classes += MirClass(
            fa.qualified, fa.cName, false, null, fa.fields.map { MirField(it.name.asString(), mirType(it.type, it)) },
            emptyList(), listOf(null, null, null), emptyList(), fa.ctorName,
        )
    }
    return classes to functions
}

/** `this::x.isInitialized` / `::x.isInitialized` of a lateinit property (when the stdlib declares it). */
internal fun Ir2Mir.lateinitIsInitialized(c: IrCall, name: String, pkg: String, out: MutableList<MirStmt>): MirExpr? {
    if (pkg != "kotlin" || name != "<get-isInitialized>") return null
    val ref = c.arguments.getOrNull(0) as? IrPropertyReference ?: return null
    val field = ref.symbol.owner.backingField ?: unsupported(c, "isInitialized without backing field")
    val recvIr = ref.arguments.firstOrNull { it != null }
    val raw = if (recvIr == null) staticFieldGet(field, c) else fieldGet(operands(listOf(recvIr), out)[0], field)
    return MirExpr.Binary(BinaryOp.IdNe, raw, MirExpr.NullConst())
}
