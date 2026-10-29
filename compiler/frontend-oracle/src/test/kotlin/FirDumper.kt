import org.jetbrains.kotlin.KtRealSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirSmartCastExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.references.toResolvedBaseSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.types.ConeCapturedType
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.ConeDefinitelyNotNullType
import org.jetbrains.kotlin.fir.types.ConeErrorType
import org.jetbrains.kotlin.fir.types.ConeFlexibleType
import org.jetbrains.kotlin.fir.types.ConeIntegerLiteralType
import org.jetbrains.kotlin.fir.types.ConeIntersectionType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionIn
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjectionOut
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.ConeStubType
import org.jetbrains.kotlin.fir.types.ConeTypeParameterType
import org.jetbrains.kotlin.fir.types.ConeTypeProjection
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.ConeTypeVariableType
import org.jetbrains.kotlin.fir.types.ConeLookupTagBasedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/** Dumps the resolved types and callees of a resolved [FirFile] (see [FirOracle]). */
class FirDumper {
    private val out = ArrayList<FirRec>()

    fun dump(file: FirFile): List<FirRec> {
        file.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                record(element)
                element.acceptChildren(this)
            }
        })
        return out
    }

    private fun KtSourceElement.isReal() = kind is KtRealSourceElementKind

    private fun record(e: FirElement) {
        val src = e.source ?: return
        if (e is FirSmartCastExpression) {
            val t = try { render(e.resolvedType) } catch (x: Throwable) { "<unresolved>" }
            out.add(FirRec(src.startOffset, src.endOffset, "FirSmartCastExpression", t, null, "stable=${e.isStable}"))
            return
        }
        if (!src.isReal()) return
        val kind = e.javaClass.simpleName.removeSuffix("Impl")
        try {
            when (e) {
                is FirExpression -> {
                    val t = try { render(e.resolvedType) } catch (x: Throwable) { "<unresolved>" }
                    var callee: String? = null
                    if (e is FirQualifiedAccessExpression) callee = calleeOf(e.calleeReference.toResolvedBaseSymbol())
                    else if (e is org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess) callee = calleeOf(e.calleeReference.toResolvedBaseSymbol())
                    else if (e is org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall) callee = calleeOf(e.calleeReference.toResolvedBaseSymbol())
                    var extra = ""
                    if (e is FirSmartCastExpression) extra = "stable=${e.isStable}"
                    out.add(FirRec(src.startOffset, src.endOffset, kind, t, callee, extra))
                }
                is FirCallableDeclaration -> {
                    val t = try { render(e.returnTypeRef.coneType) } catch (x: Throwable) { "<unresolved>" }
                    out.add(FirRec(src.startOffset, src.endOffset, "decl:$kind", t, null))
                }
                else -> {}
            }
        } catch (x: Throwable) {
            out.add(FirRec(src.startOffset, src.endOffset, kind, "<dump failure ${x.javaClass.simpleName}: ${x.message}>", null))
        }
    }

    // ---- types ---------------------------------------------------------------------------------------------------

    fun render(t: ConeKotlinType): String = Render.type(t)

    // ---- callees -------------------------------------------------------------------------------------------------

    private fun calleeOf(sym: FirBasedSymbol<*>?): String? = if (sym == null) null else Render.symbol(sym)
}

object Render {
    private val functionRe = Regex("kotlin/(Suspend)?Function(\\d+)")

    fun classId(id: org.jetbrains.kotlin.name.ClassId): String = id.asString().replace('/', '.')

    fun type(t: ConeKotlinType): String {
        val s = when (t) {
            is ConeErrorType -> "<error>"
            is ConeFlexibleType -> "ft(" + type(t.lowerBound) + ".." + type(t.upperBound) + ")"
            is ConeDefinitelyNotNullType -> type(t.original) + " & Any"
            is ConeIntersectionType -> t.intersectedTypes.map { type(it) }.sorted().joinToString(" & ", "(", ")")
            is ConeCapturedType -> "Captured(" + projection(t.constructor.projection) + ")"
            is ConeTypeParameterType -> t.lookupTag.name.asString()
            is ConeIntegerLiteralType -> "ILT(" + t.possibleTypes.joinToString(",") { type(it) } + ")"
            is ConeStubType -> "<stub>"
            is ConeTypeVariableType -> "<tv>"
            is ConeClassLikeType -> classLike(t)
            else -> t.toString()
        }
        if (t is ConeClassLikeType || t is ConeTypeParameterType || t is ConeDefinitelyNotNullType || t is ConeCapturedType) {
            return if (t.isMarkedNullable && !s.endsWith("?")) s + "?" else s
        }
        return s
    }

    private fun projection(p: ConeTypeProjection): String = when (p) {
        is ConeStarProjection -> "*"
        is ConeKotlinTypeProjectionIn -> "in " + type(p.type)
        is ConeKotlinTypeProjectionOut -> "out " + type(p.type)
        is ConeKotlinType -> type(p)
        else -> p.toString()
    }

    private fun classLike(t: ConeClassLikeType): String {
        val id = t.lookupTag.classId.asString()
        val m = functionRe.matchEntire(id)
        val args = t.typeArguments
        if (m != null && args.isNotEmpty()) {
            val isExt = t.attributes.any { it.javaClass.simpleName.contains("ExtensionFunctionType") }
            val parts = args.map { projection(it) }
            val ret = parts.last()
            var ps = parts.dropLast(1)
            var recv = ""
            if (isExt && ps.isNotEmpty()) {
                recv = ps.first() + "."
                ps = ps.drop(1)
            }
            val body = recv + "(" + ps.joinToString(", ") + ") -> " + ret
            val susp = if (m.groupValues[1].isNotEmpty()) "suspend " else ""
            val full = susp + body
            return if (t.isMarkedNullable) "($full)?" else full
        }
        val sb = StringBuilder(classId(t.lookupTag.classId))
        if (args.isNotEmpty()) sb.append(args.joinToString(",", "<", ">") { projection(it) })
        return sb.toString()
    }

    @OptIn(org.jetbrains.kotlin.fir.symbols.SymbolInternals::class)
    fun symbol(sym: FirBasedSymbol<*>): String {
        if (sym is FirCallableSymbol<*>) {
            val s = unwrap(sym)
            val fir = s.fir
            val srcStart0 = s.source?.startOffset ?: -1
            if (s.callableId == null || s.fir.let { it is FirValueParameter || (it is FirProperty && it.isLocal) }) {
                val nm = (s.fir as? FirValueParameter)?.name?.asString() ?: (s.fir as? FirProperty)?.name?.asString() ?: "?"
                return (if (s.fir is FirValueParameter) "param " else "local ") + nm + "@" + srcStart0
            }
            val cid = s.callableId!!
            val local = cid.classId?.isLocal == true || cid.packageName.asString() == "<local>"
            val name = if (cid.classId != null) classId(cid.classId!!) + "." + cid.callableName.asString()
            else (cid.packageName.asString().let { if (it.isEmpty() || it == "<root>") "" else "$it." }) + cid.callableName.asString()
            val srcStart = s.source?.startOffset ?: -1
            if (fir is FirValueParameter) return "param ${cid.callableName.asString()}@$srcStart"
            if (fir is FirProperty && fir.isLocal) return "local ${cid.callableName.asString()}@$srcStart"
            if (fir is FirFunction) {
                val sb = StringBuilder(if (fir is FirConstructor) classId(cid.classId!!) + ".<init>" else name)
                if (local && fir !is FirConstructor) sb.append("@$srcStart")
                sb.append('(')
                val recv = fir.receiverParameter?.typeRef?.coneType
                if (recv != null) sb.append(type(recv)).append(".")
                sb.append(fir.valueParameters.joinToString(",") { (if (it.isVararg) "vararg " else "") + type(it.returnTypeRef.coneType) })
                sb.append(')')
                return sb.toString()
            }
            if (fir is FirProperty) {
                val recv = fir.receiverParameter?.typeRef?.coneType
                return (if (recv != null) type(recv) + "." else "") + name
            }
            return name
        }
        return sym.javaClass.simpleName + ":" + (sym.source?.startOffset ?: -1)
    }

    private fun unwrap(sym: FirCallableSymbol<*>): FirCallableSymbol<*> = sym.unwrapFakeOverrides()
}
