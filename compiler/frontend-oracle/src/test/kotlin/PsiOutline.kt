import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.*

/**
 * Renders the PSI tree of the K2 compiler in exactly the string format of mini.frontend.Outline, so that the two
 * renderings can be compared line by line. Whenever a node kind is not handled the rendering contains `<?ClassName>`
 * and the comparison fails visibly.
 */
class PsiOutline {
    private fun join(parts: List<String>, sep: String) = parts.joinToString(sep)

    fun file(f: KtFile): List<String> {
        val out = ArrayList<String>()
        val pkg = f.packageDirective?.qualifiedName ?: ""
        out.add("package " + (if (pkg.isEmpty()) "-" else pkg))
        for (a in f.fileAnnotationList?.annotationEntries ?: emptyList()) out.add("fileann " + annotation(a))
        for (i in f.importDirectives) {
            val path = i.importPath
            val name = path?.pathStr ?: "?"
            out.add("import " + (if (path?.isAllUnder == true) name.removeSuffix(".*") + ".*" else name) + (if (path?.hasAlias() == true) " as " + path.alias?.asString() else ""))
        }
        for (d in f.declarations) out.add(decl(d))
        return out
    }

    // ---- modifiers --------------------------------------------------------------------------------------------

    private fun contextOf(ml: KtModifierList?): String {
        if (ml == null) return ""
        val parts = ArrayList<String>()
        val lists = (ml.contextParameterLists + ml.contextReceiverLists).distinct()
        for (l in lists) {
            val ps = l.contextParameters()
            if (ps.isNotEmpty()) {
                for (p in ps) parts.add(param(p))
            } else {
                for (t in l.typeReferences()) parts.add("_:" + type(t))
            }
        }
        return if (parts.isEmpty()) "" else "ctx(" + join(parts, ",") + ")"
    }

    fun mods(ml: KtModifierList?): String {
        if (ml == null) return ""
        val parts = ArrayList<String>()
        val ctx = contextOf(ml)
        if (ctx.isNotEmpty()) parts.add(ctx)
        var c: PsiElement? = ml.firstChild
        while (c != null) {
            when (c) {
                is KtAnnotationEntry -> parts.add(annotation(c))
                is KtAnnotation -> for (e in c.entries) parts.add(annotation(e))
                is KtContextParameterList -> {}
                is PsiWhiteSpace, is PsiComment -> {}
                else -> {
                    if (c.node.elementType is org.jetbrains.kotlin.lexer.KtModifierKeywordToken) parts.add(c.text)
                    else if (c.text.isNotBlank()) parts.add("<?mod:" + c.javaClass.simpleName + ">")
                }
            }
            c = c.nextSibling
        }
        return if (parts.isEmpty()) "" else " [" + join(parts, " ") + "]"
    }

    private fun annotation(a: KtAnnotationEntry): String {
        val sb = StringBuilder("@")
        val t = a.useSiteTarget
        if (t != null) sb.append(t.text.removeSuffix(":")).append(':')
        sb.append(type(a.typeReference))
        val vl = a.valueArgumentList
        if (vl != null) sb.append('(').append(args(vl.arguments)).append(')')
        return sb.toString()
    }

    private fun args(list: List<ValueArgument>): String {
        val parts = ArrayList<String>()
        for (a in list) {
            val sb = StringBuilder()
            val n = a.getArgumentName()
            if (n != null) sb.append(n.referenceExpression?.text).append('=')
            if (a.getSpreadElement() != null) sb.append('*')
            sb.append(expr(a.getArgumentExpression()))
            parts.add(sb.toString())
        }
        return join(parts, ",")
    }

    // ---- types ------------------------------------------------------------------------------------------------

    fun type(t: KtTypeReference?): String {
        if (t == null) return "_"
        val sb = StringBuilder()
        val ml = t.modifierList
        if (ml != null) {
            var c: PsiElement? = ml.firstChild
            while (c != null) {
                when (c) {
                    is KtAnnotationEntry -> sb.append(annotation(c)).append(' ')
                    is KtAnnotation -> for (e in c.entries) sb.append(annotation(e)).append(' ')
                    is KtContextParameterList -> {}
                    is PsiWhiteSpace, is PsiComment -> {}
                    else -> if (c.node.elementType is org.jetbrains.kotlin.lexer.KtModifierKeywordToken) sb.append(c.text).append(' ')
                }
                c = c.nextSibling
            }
        }
        sb.append(typeElement(t.typeElement))
        return sb.toString()
    }

    private fun typeElement(t: KtTypeElement?): String = when (t) {
        null -> "_"
        is KtUserType -> {
            val chain = ArrayList<KtUserType>()
            var u: KtUserType? = t
            while (u != null) {
                chain.add(0, u)
                u = u.qualifier
            }
            val segs = ArrayList<String>()
            for (x in chain) {
                val name = x.referenceExpression?.text ?: "?"
                val ta = x.typeArguments
                if (ta.isEmpty()) {
                    segs.add(name)
                } else {
                    val a = ArrayList<String>()
                    for (p in ta) {
                        if (p.projectionKind == KtProjectionKind.STAR) a.add("*")
                        else a.add((if (p.projectionKind == KtProjectionKind.IN) "in " else if (p.projectionKind == KtProjectionKind.OUT) "out " else "") + type(p.typeReference))
                    }
                    segs.add(name + "<" + join(a, ",") + ">")
                }
            }
            join(segs, ".")
        }
        is KtNullableType -> typeElement(t.innerType) + "?"
        is KtFunctionType -> {
            val sb = StringBuilder()
            val ctx = t.contextReceiverList
            if (ctx != null) {
                val c = ArrayList<String>()
                for (x in ctx.typeReferences()) c.add(type(x))
                sb.append("context(").append(join(c, ",")).append(") ")
            }
            if (t.receiverTypeReference != null) sb.append(type(t.receiverTypeReference)).append('.')
            val ps = ArrayList<String>()
            for (p in t.parameters) ps.add((if (p.nameIdentifier != null) p.nameIdentifier!!.text + ":" else "") + type(p.typeReference))
            sb.append('(').append(join(ps, ",")).append(")->").append(type(t.returnTypeReference))
            sb.toString()
        }
        is KtDynamicType -> "dynamic"
        is KtIntersectionType -> type(t.getLeftTypeRef()) + "&" + type(t.getRightTypeRef())
        else -> "<?" + t.javaClass.simpleName + ">"
    }

    // ---- declarations -----------------------------------------------------------------------------------------

    private fun typeParams(owner: KtTypeParameterListOwner): String {
        val list = owner.typeParameters
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (p in list) {
            val mm = mods(p.modifierList)
            parts.add((if (mm.isEmpty()) "" else mm.substring(1) + " ") + (p.nameIdentifier?.text ?: "?") + (if (p.extendsBound != null) ":" + type(p.extendsBound) else ""))
        }
        return " <" + join(parts, ", ") + ">"
    }

    private fun constraints(owner: KtTypeParameterListOwner): String {
        val list = owner.typeConstraints
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (c in list) parts.add((c.subjectTypeParameterName?.text ?: "?") + ":" + type(c.boundTypeReference))
        return " where " + join(parts, ", ")
    }

    fun param(p: KtParameter): String {
        val sb = StringBuilder()
        val mm = mods(p.modifierList)
        if (mm.isNotEmpty()) sb.append(mm.substring(1)).append(' ')
        val vv = p.valOrVarKeyword
        if (vv != null) sb.append(vv.text).append(' ')
        sb.append(p.nameIdentifier?.text ?: "?")
        if (p.typeReference != null) sb.append(':').append(type(p.typeReference))
        if (p.defaultValue != null) sb.append('=').append(expr(p.defaultValue))
        return sb.toString()
    }

    private fun params(list: List<KtParameter>): String = "(" + join(list.map { param(it) }, ", ") + ")"

    private fun destruct(d: KtDestructuringDeclaration): String {
        val parts = ArrayList<String>()
        for (e in d.entries) {
            val mm = mods(e.modifierList)
            parts.add((if (mm.isEmpty()) "" else mm.substring(1) + " ") + (if (e.isVar) "var " else "") + (e.nameIdentifier?.text ?: "?") + (if (e.typeReference != null) ":" + type(e.typeReference) else ""))
        }
        return "(" + join(parts, ", ") + ")"
    }

    private fun superEntries(owner: KtClassOrObject): String {
        val list = owner.superTypeListEntries
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        for (s in list) {
            when (s) {
                is KtSuperTypeCallEntry -> parts.add(type(s.typeReference) + "(" + args(s.valueArguments) + ")")
                is KtDelegatedSuperTypeEntry -> parts.add(type(s.typeReference) + " by " + expr(s.delegateExpression))
                else -> parts.add(type(s.typeReference))
            }
        }
        return " : " + join(parts, ", ")
    }

    private fun members(list: List<KtDeclaration>): String = join(list.map { decl(it) }, "; ")

    fun decl(d: KtDeclaration): String = when (d) {
        is KtEnumEntry -> {
            val sb = StringBuilder("entry ")
            sb.append(d.nameIdentifier?.text ?: "?").append(mods(d.modifierList))
            val il = d.initializerList
            if (il != null) {
                val call = il.initializers.firstOrNull() as? KtSuperTypeCallEntry
                sb.append('(').append(args(call?.valueArguments ?: emptyList())).append(')')
            }
            val b = d.body
            if (b != null) sb.append(" { ").append(members(b.declarations)).append(" }")
            sb.toString()
        }
        is KtClassOrObject -> {
            val sb = StringBuilder()
            sb.append(if (d is KtClass && d.isInterface()) "interface" else if (d is KtObjectDeclaration) "object" else "class")
            sb.append(' ').append(d.nameIdentifier?.text ?: "_").append(mods(d.modifierList)).append(typeParams(d))
            val c = d.primaryConstructor
            if (c != null) sb.append(" ctor").append(mods(c.modifierList)).append(params(c.valueParameters))
            sb.append(superEntries(d)).append(constraints(d))
            val b = d.body
            if (b != null) sb.append(" { ").append(members(b.declarations)).append(" }")
            sb.toString()
        }
        is KtNamedFunction -> fn(d)
        is KtProperty -> {
            val sb = StringBuilder(if (d.isVar) "var" else "val")
            sb.append(mods(d.modifierList)).append(typeParams(d)).append(' ')
            if (d.receiverTypeReference != null) sb.append(type(d.receiverTypeReference)).append('.')
            sb.append(d.nameIdentifier?.text ?: "?")
            if (d.typeReference != null) sb.append(':').append(type(d.typeReference))
            sb.append(constraints(d))
            if (d.initializer != null) sb.append(" = ").append(expr(d.initializer))
            if (d.delegateExpression != null) sb.append(" by ").append(expr(d.delegateExpression))
            if (d.getter != null) sb.append(' ').append(accessor(d.getter!!))
            if (d.setter != null) sb.append(' ').append(accessor(d.setter!!))
            sb.toString()
        }
        is KtDestructuringDeclaration -> {
            val sb = StringBuilder(if (d.isVar) "var" else "val")
            sb.append(mods(d.modifierList)).append(' ').append(destruct(d))
            if (d.initializer != null) sb.append(" = ").append(expr(d.initializer))
            sb.toString()
        }
        is KtTypeAlias -> "typealias " + (d.nameIdentifier?.text ?: "?") + mods(d.modifierList) + typeParams(d) + " = " + type(d.getTypeReference())
        is KtClassInitializer -> "init " + expr(d.body)
        is KtSecondaryConstructor -> {
            val sb = StringBuilder("constructor")
            sb.append(mods(d.modifierList)).append(params(d.valueParameters))
            val dg = d.getDelegationCall()
            if (!dg.isImplicit) sb.append(if (dg.isCallToThis) " : this(" else " : super(").append(args(dg.valueArguments)).append(')')
            if (d.bodyExpression != null) sb.append(' ').append(expr(d.bodyExpression))
            sb.toString()
        }
        else -> "<?" + d.javaClass.simpleName + ">"
    }

    private fun accessor(a: KtPropertyAccessor): String {
        val sb = StringBuilder(if (a.isGetter) "get" else "set")
        sb.append(mods(a.modifierList))
        val p = a.parameter
        if (p != null) sb.append('(').append(param(p)).append(')') else sb.append("()")
        if (a.returnTypeReference != null) sb.append(':').append(type(a.returnTypeReference))
        if (a.bodyExpression != null) sb.append(if (a.hasBlockBody()) " " else " = ").append(expr(a.bodyExpression))
        return sb.toString()
    }

    private fun fn(d: KtNamedFunction): String {
        val sb = StringBuilder("fun")
        sb.append(mods(d.modifierList)).append(typeParams(d)).append(' ')
        if (d.receiverTypeReference != null) sb.append(type(d.receiverTypeReference)).append('.')
        sb.append(d.nameIdentifier?.text ?: "").append(params(d.valueParameters))
        if (d.typeReference != null) sb.append(':').append(type(d.typeReference))
        sb.append(constraints(d))
        if (d.bodyExpression != null) sb.append(if (d.hasBlockBody()) " " else " = ").append(expr(d.bodyExpression))
        return sb.toString()
    }

    // ---- expressions ------------------------------------------------------------------------------------------

    private fun stmts(list: List<KtExpression>): String = join(list.map { stmt(it) }, "; ")

    private fun stmt(e: KtExpression): String = if (e is KtDeclaration) decl(e) else expr(e)


    private fun label(l: String?): String = if (l != null) "@" + l else ""

    fun expr(e: KtExpression?): String {
        if (e == null) return "_"
        return when (e) {
            is KtConstantExpression -> e.text
            is KtStringTemplateExpression -> {
                val raw = e.text.startsWith("\"\"\"")
                val sb = StringBuilder(if (raw) "\"\"\"" else "\"")
                for (p in e.entries) {
                    when (p) {
                        is KtLiteralStringTemplateEntry -> sb.append(p.text)
                        is KtEscapeStringTemplateEntry -> sb.append(p.text)
                        is KtSimpleNameStringTemplateEntry -> sb.append('$').append(p.expression?.text ?: "?")
                        is KtBlockStringTemplateEntry -> sb.append("\${").append(expr(p.expression)).append('}')
                        else -> sb.append("<?" + p.javaClass.simpleName + ">")
                    }
                }
                sb.append(if (raw) "\"\"\"" else "\"")
                sb.toString()
            }
            is KtNameReferenceExpression -> e.text
            is KtThisExpression -> "this" + label(e.getLabelName())
            is KtSuperExpression -> "super" + (if (e.superTypeQualifier != null) "<" + type(e.superTypeQualifier) + ">" else "") + label(e.getLabelName())
            is KtParenthesizedExpression -> expr(e.expression)
            is KtBinaryExpression -> "(" + e.operationReference.text + " " + expr(e.left) + " " + expr(e.right) + ")"
            is KtIsExpression -> "(" + (if (e.isNegated) "!is " else "is ") + expr(e.leftHandSide) + " " + type(e.typeReference) + ")"
            is KtBinaryExpressionWithTypeRHS -> "(" + e.operationReference.text + " " + expr(e.left) + " " + type(e.right) + ")"
            is KtPrefixExpression -> "(pre" + e.operationReference.text + " " + expr(e.baseExpression) + ")"
            is KtPostfixExpression -> "(post" + e.operationReference.text + " " + expr(e.baseExpression) + ")"
            is KtAnnotatedExpression -> "(" + join(e.annotationEntries.map { annotation(it) }, " ") + " " + expr(e.baseExpression) + ")"
            is KtLabeledExpression -> "(" + e.getLabelName() + "@ " + expr(e.baseExpression) + ")"
            is KtCallExpression -> {
                val sb = StringBuilder("(call ").append(expr(e.calleeExpression))
                val ta = e.typeArguments
                if (ta.isNotEmpty()) {
                    val a = ArrayList<String>()
                    for (x in ta) {
                        if (x.projectionKind == KtProjectionKind.STAR) a.add("*")
                        else a.add((if (x.projectionKind == KtProjectionKind.IN) "in " else if (x.projectionKind == KtProjectionKind.OUT) "out " else "") + type(x.typeReference))
                    }
                    sb.append(" <").append(join(a, ",")).append('>')
                }
                val vl = e.valueArgumentList
                if (vl != null) sb.append(" (").append(args(vl.arguments)).append(')')
                for (l in e.lambdaArguments) sb.append(' ').append(expr(l.getArgumentExpression()))
                sb.append(')')
                sb.toString()
            }
            is KtArrayAccessExpression -> "(idx " + expr(e.arrayExpression) + " " + join(e.indexExpressions.map { expr(it) }, " ") + ")"
            is KtDotQualifiedExpression -> "(. " + expr(e.receiverExpression) + " " + expr(e.selectorExpression) + ")"
            is KtSafeQualifiedExpression -> "(?. " + expr(e.receiverExpression) + " " + expr(e.selectorExpression) + ")"
            is KtClassLiteralExpression -> "(:: " + (if (e.receiverExpression != null) expr(e.receiverExpression) else "_") + " class)"
            is KtCallableReferenceExpression -> "(:: " + (if (e.receiverExpression != null) expr(e.receiverExpression) else "_") + " " + e.callableReference.text + ")"
            is KtLambdaExpression -> {
                val ps = ArrayList<String>()
                for (p in e.valueParameters) {
                    val mm = mods(p.modifierList)
                    val sb = StringBuilder()
                    if (mm.isNotEmpty()) sb.append(mm.substring(1)).append(' ')
                    val dd = p.destructuringDeclaration
                    if (dd != null) sb.append(destruct(dd)) else sb.append(p.nameIdentifier?.text ?: "?")
                    if (p.typeReference != null) sb.append(':').append(type(p.typeReference))
                    ps.add(sb.toString())
                }
                "(lam " + join(ps, ",") + " | " + stmts(e.bodyExpression?.statements ?: emptyList()) + ")"
            }
            is KtNamedFunction -> fn(e)
            is KtBlockExpression -> "{" + stmts(e.statements) + "}"
            is KtIfExpression -> "(if " + expr(e.condition) + " " + expr(e.then) + (if (e.`else` != null) " " + expr(e.`else`) else "") + ")"
            is KtWhenExpression -> {
                val sb = StringBuilder("(when ")
                val sv = e.subjectVariable
                if (sv != null) {
                    sb.append("val ").append(sv.nameIdentifier?.text ?: "?").append(if (sv.typeReference != null) ":" + type(sv.typeReference) else "").append('=').append(expr(sv.initializer))
                } else if (e.subjectExpression != null) {
                    sb.append(expr(e.subjectExpression))
                } else {
                    sb.append('_')
                }
                for (en in e.entries) {
                    sb.append(" (")
                    if (en.isElse) {
                        sb.append("else")
                    } else {
                        val cs = ArrayList<String>()
                        for (c in en.conditions) {
                            when (c) {
                                is KtWhenConditionWithExpression -> cs.add(expr(c.expression))
                                is KtWhenConditionInRange -> cs.add((if (c.isNegated) "!in " else "in ") + expr(c.rangeExpression))
                                is KtWhenConditionIsPattern -> cs.add((if (c.isNegated) "!is " else "is ") + type(c.typeReference))
                                else -> cs.add("<?" + c.javaClass.simpleName + ">")
                            }
                        }
                        sb.append(join(cs, ","))
                        val g = en.guard
                        if (g != null) sb.append(" if ").append(expr(g.getExpression()))
                    }
                    sb.append(" -> ").append(expr(en.expression)).append(')')
                }
                sb.append(')')
                sb.toString()
            }
            is KtTryExpression -> {
                val sb = StringBuilder("(try ").append(expr(e.tryBlock))
                for (c in e.catchClauses) sb.append(" (catch ").append(param(c.catchParameter!!)).append(' ').append(expr(c.catchBody)).append(')')
                val f = e.finallyBlock
                if (f != null) sb.append(" (finally ").append(expr(f.finalExpression)).append(')')
                sb.append(')')
                sb.toString()
            }
            is KtThrowExpression -> "(throw " + expr(e.thrownExpression) + ")"
            is KtReturnExpression -> "(return" + label(e.getLabelName()) + (if (e.returnedExpression != null) " " + expr(e.returnedExpression) else "") + ")"
            is KtBreakExpression -> "(break" + label(e.getLabelName()) + ")"
            is KtContinueExpression -> "(continue" + label(e.getLabelName()) + ")"
            is KtForExpression -> {
                val dd = e.destructuringDeclaration
                val lp = e.loopParameter
                val v = if (dd != null) destruct(dd) else ((lp?.nameIdentifier?.text ?: "?") + (if (lp?.typeReference != null) ":" + type(lp.typeReference) else ""))
                "(for " + v + " in " + expr(e.loopRange) + " " + expr(e.body) + ")"
            }
            is KtWhileExpression -> "(while " + expr(e.condition) + " " + expr(e.body) + ")"
            is KtDoWhileExpression -> "(do " + expr(e.body) + " while " + expr(e.condition) + ")"
            is KtObjectLiteralExpression -> {
                val od = e.objectDeclaration
                "(object" + superEntries(od) + " { " + members(od.body?.declarations ?: emptyList()) + " })"
            }
            is KtCollectionLiteralExpression -> "[" + join(e.getInnerExpressions().map { expr(it) }, ",") + "]"
            is KtDeclaration -> decl(e)
            else -> "<?" + e.javaClass.simpleName + ">"
        }
    }
}
