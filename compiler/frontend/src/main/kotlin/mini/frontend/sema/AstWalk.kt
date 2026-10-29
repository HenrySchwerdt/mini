package mini.frontend.sema

import mini.frontend.*

/** Generic child enumeration over the AST (used for cheap syntactic scans, e.g. which variables a loop assigns). */
object AstWalk {
    fun children(n: Ast, out: ArrayList<Ast>) {
        when (n) {
            is ParenExpr -> out.add(n.inner)
            is BinaryExpr -> {
                out.add(n.left)
                out.add(n.right)
            }
            is IsExpr -> out.add(n.expr)
            is AsExpr -> out.add(n.expr)
            is PrefixExpr -> out.add(n.expr)
            is PostfixExpr -> out.add(n.expr)
            is AnnotatedExpr -> out.add(n.expr)
            is LabeledExpr -> out.add(n.expr)
            is CallExpr -> {
                out.add(n.callee)
                val a = n.args
                if (a != null) for (x in a) out.add(x.value)
                for (l in n.lambdas) out.add(l)
            }
            is IndexExpr -> {
                out.add(n.receiver)
                for (i in n.indices) out.add(i)
            }
            is DotExpr -> {
                out.add(n.receiver)
                out.add(n.selector)
            }
            is CallableRefExpr -> {
                val r = n.receiver
                if (r != null) out.add(r)
            }
            is LambdaExpr -> for (s in n.body) out.add(s)
            is AnonFunExpr -> out.add(n.fn)
            is BlockExpr -> for (s in n.stmts) out.add(s)
            is IfExpr -> {
                out.add(n.cond)
                val t = n.then
                if (t != null) out.add(t)
                val o = n.otherwise
                if (o != null) out.add(o)
            }
            is WhenExpr -> {
                val s = n.subject
                if (s != null) out.add(s.expr)
                for (e in n.entries) {
                    for (c in e.conds) {
                        when (c) {
                            is WhenExprCond -> out.add(c.expr)
                            is WhenInCond -> out.add(c.expr)
                            is WhenIsCond -> {}
                        }
                    }
                    val g = e.guard
                    if (g != null) out.add(g)
                    out.add(e.body)
                }
            }
            is TryExpr -> {
                out.add(n.body)
                for (c in n.catches) out.add(c.body)
                val f = n.finallyBlock
                if (f != null) out.add(f)
            }
            is ThrowExpr -> {
                val e = n.expr
                if (e != null) out.add(e)
            }
            is ReturnExpr -> {
                val e = n.expr
                if (e != null) out.add(e)
            }
            is ForExpr -> {
                out.add(n.iterable)
                val b = n.body
                if (b != null) out.add(b)
            }
            is WhileExpr -> {
                out.add(n.cond)
                val b = n.body
                if (b != null) out.add(b)
            }
            is DoWhileExpr -> {
                val b = n.body
                if (b != null) out.add(b)
                out.add(n.cond)
            }
            is ObjectLiteral -> {
                for (m in n.members) out.add(m)
            }
            is CollectionLiteral -> for (e in n.elements) out.add(e)
            is StringExpr -> for (p in n.parts) if (p is StrLongExpr) out.add(p.expr)
            is FunDecl -> {
                val b = n.body
                if (b != null) out.add(b)
            }
            is PropertyDecl -> {
                val i = n.initializer
                if (i != null) out.add(i)
                val d = n.delegate
                if (d != null) out.add(d)
                val g = n.getter?.body
                if (g != null) out.add(g)
                val s = n.setter?.body
                if (s != null) out.add(s)
            }
            is DestructuringDecl -> {
                val i = n.initializer
                if (i != null) out.add(i)
            }
            is ClassDecl -> for (m in n.members) out.add(m)
            is InitDecl -> {
                val b = n.body
                if (b != null) out.add(b)
            }
            else -> {}
        }
    }

    /** names assigned (`x = `, `x += `, `x++`) anywhere below [n] */
    fun assignedNames(n: Ast, out: HashSet<String>) {
        if (n is BinaryExpr && isAssignOp(n.op)) {
            val l = unparen(n.left)
            if (l is NameExpr) out.add(l.name)
        }
        if (n is PrefixExpr && (n.op == "++" || n.op == "--")) {
            val l = unparen(n.expr)
            if (l is NameExpr) out.add(l.name)
        }
        if (n is PostfixExpr && (n.op == "++" || n.op == "--")) {
            val l = unparen(n.expr)
            if (l is NameExpr) out.add(l.name)
        }
        val ch = ArrayList<Ast>()
        children(n, ch)
        for (c in ch) assignedNames(c, out)
    }

    /** names assigned inside lambdas / local functions / object literals below [n] */
    fun capturedAssigned(n: Ast, out: HashSet<String>) {
        if (n is LambdaExpr || n is AnonFunExpr || n is ObjectLiteral || (n is FunDecl)) {
            assignedNames(n, out)
            return
        }
        val ch = ArrayList<Ast>()
        children(n, ch)
        for (c in ch) capturedAssigned(c, out)
    }

    fun isAssignOp(op: String): Boolean = op == "=" || op == "+=" || op == "-=" || op == "*=" || op == "/=" || op == "%="

    fun unparen(e: Expr): Expr {
        var x = e
        while (x is ParenExpr) x = x.inner
        return x
    }
}
