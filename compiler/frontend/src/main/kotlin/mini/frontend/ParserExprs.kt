package mini.frontend

// Expression and statement grammar. Precedence, from loosest to tightest (as in the reference parser):
//   assignment < || < && < equality < comparison < in/is < ?: < infix function < .. < + - < * / % < as < prefix < postfix

internal fun Parser.parseExpression(): Expr {
    val m = mark()
    val s = tokStart()
    if (depth >= MAX_DEPTH) {
        if (kind() == Tk.EOF) error("Expression is nested too deeply") else skipError("Expression is nested too deeply")
        done(m, NodeKind.ERROR)
        return fin(ErrorExpr(), s)
    }
    depth++
    val e = parseAssignmentLevel(m, s)
    depth--
    return e
}

private fun Parser.parseAssignmentLevel(m: Int, s: Int): Expr {
    var left = parseBin(1)
    while (!interrupted()) {
        val k = kind()
        if (k == Tk.EQ || k == Tk.PLUSEQ || k == Tk.MINUSEQ || k == Tk.STAREQ || k == Tk.SLASHEQ || k == Tk.PERCEQ) {
            val op = tokText()
            advance()
            val right = parseBin(1)
            done(m, NodeKind.BINARY)
            left = fin(BinaryExpr(op, left, right), s)
        } else {
            break
        }
    }
    return left
}

/**
 * Binary operator at the current position: returns the number of tokens it consists of (0: none); its text goes to
 * [Parser.opText] and its precedence level (1 `||` ... 10 `*`) to [Parser.opLevel].
 */
private fun Parser.binOp(): Int {
    val k = kind()
    when (k) {
        Tk.OROR -> {
            opText = "||"
            opLevel = 1
            return 1
        }
        Tk.ANDAND -> {
            opText = "&&"
            opLevel = 2
            return 1
        }
        Tk.EQEQ, Tk.NEQ, Tk.EQEQEQ, Tk.NEQEQ -> {
            opText = tokText()
            opLevel = 3
            return 1
        }
        Tk.LT, Tk.LTEQ -> {
            opText = tokText()
            opLevel = 4
            return 1
        }
        Tk.GT -> {
            opLevel = 4
            if (atGtEq()) {
                opText = ">="
                return 2
            }
            opText = ">"
            return 1
        }
        Tk.IN, Tk.NOT_IN, Tk.IS, Tk.NOT_IS -> {
            opText = tokText()
            opLevel = 5
            return 1
        }
        Tk.QUEST -> {
            if (atElvis()) {
                opText = "?:"
                opLevel = 6
                return 2
            }
            return 0
        }
        Tk.IDENT -> {
            opText = tokText()
            opLevel = 7
            return 1
        }
        Tk.DOTDOT, Tk.DOTDOTLT -> {
            opText = tokText()
            opLevel = 8
            return 1
        }
        Tk.PLUS, Tk.MINUS -> {
            opText = tokText()
            opLevel = 9
            return 1
        }
        Tk.STAR, Tk.SLASH, Tk.PERC -> {
            opText = tokText()
            opLevel = 10
            return 1
        }
        else -> return 0
    }
}

/** precedence climbing over the left-associative binary operators; [minLevel] is the loosest level accepted */
private fun Parser.parseBin(minLevel: Int): Expr {
    val m = mark()
    val s = tokStart()
    var left = parseAs()
    isClosed = false
    while (!interrupted()) {
        val n = binOp()
        if (n == 0 || opLevel < minLevel) break
        // after `is T` / `!is T` only operators of the same or a looser level may follow: the type on the right is not an
        // operand a tighter operator could attach to (`a !is B to c` leaves the `to` for the enclosing construct)
        if (isClosed && opLevel > 5) break
        val op = opText
        val level = opLevel
        val k = kind()
        var i = 0
        while (i < n) {
            advance()
            i++
        }
        if (level == 5 && (k == Tk.IS || k == Tk.NOT_IS)) {
            val t = parseType()
            done(m, NodeKind.IS)
            left = fin(IsExpr(k == Tk.NOT_IS, left, t), s)
            isClosed = true
        } else {
            val right = parseBin(level + 1)
            done(m, NodeKind.BINARY)
            left = fin(BinaryExpr(op, left, right), s)
        }
    }
    return left
}

private fun Parser.parseAs(): Expr {
    val m = mark()
    val s = tokStart()
    var left = parsePrefix()
    while (kind() == Tk.AS && !interrupted()) {
        advance()
        var safe = false
        if (kind() == Tk.QUEST && adjacentPrevToCurrent()) {
            advance()
            safe = true
        }
        val t = parseType()
        done(m, NodeKind.AS)
        left = fin(AsExpr(safe, left, t), s)
    }
    return left
}

private fun Parser.parsePrefix(): Expr = parsePrefixInner()

/** the operand of a prefix operator or annotation; recursion here is bounded like everything else */
private fun Parser.parsePrefixOperand(): Expr {
    if (depth >= MAX_DEPTH) {
        val m0 = mark()
        val s0 = tokStart()
        if (kind() == Tk.EOF) error("Expression is nested too deeply") else skipError("Expression is nested too deeply")
        done(m0, NodeKind.ERROR)
        return fin(ErrorExpr(), s0)
    }
    depth++
    val e = parsePrefixInner()
    depth--
    return e
}

private fun Parser.parsePrefixInner(): Expr {
    val k = kind()
    val s = tokStart()
    if (k == Tk.MINUS || k == Tk.PLUS || k == Tk.PLUSPLUS || k == Tk.MINUSMINUS || k == Tk.EXCL) {
        val m = mark()
        val op = tokText()
        advance()
        val e = parsePrefixOperand()
        done(m, NodeKind.PREFIX)
        return fin(PrefixExpr(op, e), s)
    }
    if (k == Tk.EXCLEXCL) {
        // `!!x` is two negations (one token)
        val m = mark()
        advance()
        val is2 = tokStart()
        val inner = mark()
        val e = parsePrefixOperand()
        done(inner, NodeKind.PREFIX)
        done(m, NodeKind.PREFIX)
        val innerNode = fin(PrefixExpr("!", e), is2)
        return fin(PrefixExpr("!", innerNode), s)
    }
    if (k == Tk.AT) {
        val m = mark()
        val anns = ArrayList<AnnotationEntry>()
        while (kind() == Tk.AT) {
            val before = p
            parseAnnotationInto(anns, false)
            if (p == before) break
        }
        if (kind() == Tk.FUN) {
            // an anonymous function takes the annotations as its modifiers
            val items = ArrayList<ModItem>()
            for (a in anns) items.add(fin(AnnotationMod(a), a.start))
            done(m, NodeKind.MODIFIERS)
            return parseAnonFun(m, s, fin(Modifiers(items, emptyList()), s))
        }
        val e = parsePrefixOperand()
        done(m, NodeKind.ANNOTATED)
        return fin(AnnotatedExpr(anns, e), s)
    }
    if (k == Tk.IDENT && la(1) == Tk.AT && adjacent(0)) {
        // label definition `name@`
        val m = mark()
        val lm = mark()
        val label = tokText()
        advance()
        advance()
        done(lm, NodeKind.LABEL)
        val e = parsePrefixOperand()
        done(m, NodeKind.LABELED)
        return fin(LabeledExpr(label, e), s)
    }
    return parsePostfix()
}

/** `(`, `{` or `label@ {` right after a callee */
private fun Parser.atCallSuffixStart(): Boolean {
    val k = kind()
    if (k == Tk.LPAR) return true
    if (k == Tk.LBRACE) return !noLambda
    return k == Tk.IDENT && la(1) == Tk.AT && adjacent(0) && la(2) == Tk.LBRACE && !noLambda
}

private fun Parser.atLambdaArg(): Boolean {
    if (noLambda) return false
    if (kind() == Tk.LBRACE) return true
    if (kind() == Tk.IDENT && la(1) == Tk.AT && adjacent(0) && la(2) == Tk.LBRACE) return true
    return false
}

/** at `@`: annotations followed by a lambda (`foo() @Ann { }`)? Consumes nothing. */
private fun Parser.atAnnotatedLambda(): Boolean {
    if (kind() != Tk.AT || noLambda) return false
    val sn = snap()
    val anns = ArrayList<AnnotationEntry>()
    while (kind() == Tk.AT) {
        val before = p
        parseAnnotationInto(anns, false)
        if (p == before) break
    }
    val ok = kind() == Tk.LBRACE && errors.size == sn.errSize
    restore(sn)
    return ok
}

/** trailing lambda arguments (after the argument list or directly after a callee); newlines before them are allowed */
private fun Parser.parseLambdaArgs(into: ArrayList<Expr>) {
    while (atLambdaArg() || atAnnotatedLambda()) {
        val m = mark()
        val s = tokStart()
        if (kind() == Tk.AT) {
            val am = mark()
            val anns = ArrayList<AnnotationEntry>()
            while (kind() == Tk.AT) {
                val before = p
                parseAnnotationInto(anns, false)
                if (p == before) break
            }
            val l = parseLambda()
            done(am, NodeKind.ANNOTATED)
            into.add(fin(AnnotatedExpr(anns, l), s))
        } else if (kind() == Tk.IDENT) {
            val label = tokText()
            val lm = mark()
            advance()
            advance()
            done(lm, NodeKind.LABEL)
            val l = parseLambda()
            done(m, NodeKind.LABELED)
            into.add(fin(LabeledExpr(label, l), s))
        } else {
            into.add(parseLambda())
        }
        done(m, NodeKind.LAMBDA_ARG)
    }
}

private fun Parser.parsePostfix(): Expr {
    val m = mark()
    val s = tokStart()
    var e = parseAtom()
    while (true) {
        val k = kind()
        if (k == Tk.LBRACKET && !interrupted()) {
            val saved = nlOn
            val savedNoLambda = noLambda
            nlOn = false
            noLambda = false
            advance()
            val idx = ArrayList<Expr>()
            while (kind() != Tk.RBRACKET && kind() != Tk.EOF) {
                val before = p
                idx.add(parseExpression())
                if (kind() == Tk.COMMA) advance() else break
                if (p == before) break
            }
            expect(Tk.RBRACKET, "']'")
            nlOn = saved
            noLambda = savedNoLambda
            done(m, NodeKind.INDEX)
            e = fin(IndexExpr(e, idx), s)
        } else if (atCallSuffixStart() && !interrupted()) {
            e = parseCallSuffix(m, s, e, emptyList())
        } else if (atCallSuffixStart()) {
            break
        } else if (k == Tk.LT && !interrupted()) {
            val ta = tryParseTypeArgsInExpr()
            if (ta == null) break
            e = parseCallSuffix(m, s, e, ta)
        } else if (k == Tk.DOT || atSafeDot()) {
            val safe = k != Tk.DOT
            if (safe) advance()
            advance()
            val sm = mark()
            val ss = tokStart()
            var sel: Expr
            if (kind() == Tk.IDENT || startsExpression(kind())) {
                // the reference parser accepts any atom as selector (`a.(b)`, `a. { }`); analysis rejects the odd ones
                sel = parseAtom()
                var ta: List<TypeArg> = emptyList()
                var hasTa = false
                if (kind() == Tk.LT && !interrupted()) {
                    val t = tryParseTypeArgsInExpr()
                    if (t != null) {
                        ta = t
                        hasTa = true
                    }
                }
                if ((atCallSuffixStart() && !interrupted()) || hasTa) {
                    sel = parseCallSuffix(sm, ss, sel, ta)
                }
            } else {
                errorPrev("Expecting an identifier")
                done(sm, NodeKind.ERROR)
                sel = fin(ErrorExpr(), ss)
            }
            done(m, NodeKind.DOT)
            e = fin(DotExpr(e, safe, sel), s)
        } else if ((k == Tk.PLUSPLUS || k == Tk.MINUSMINUS || k == Tk.EXCLEXCL) && !interrupted()) {
            val op = tokText()
            advance()
            done(m, NodeKind.POSTFIX)
            e = fin(PostfixExpr(op, e), s)
        } else if ((k == Tk.COLONCOLON || (k == Tk.QUEST && la(1) == Tk.COLONCOLON && adjacent(0))) && !interrupted()) {
            if (k == Tk.QUEST) advance()
            advance()
            var name = "<missing>"
            if (kind() == Tk.IDENT || kind() == Tk.CLASS) {
                name = tokText()
                advance()
            } else {
                errorPrev("Expecting an identifier or 'class'")
            }
            done(m, NodeKind.CALLABLE_REF)
            e = fin(CallableRefExpr(e, name), s)
        } else {
            break
        }
    }
    return e
}

/** at `(`, `{` (or after explicit type arguments): argument list and trailing lambdas of a call whose callee is [callee] */
private fun Parser.parseCallSuffix(m: Int, s: Int, callee: Expr, typeArgs: List<TypeArg>): Expr {
    var args: List<Arg>? = null
    if (kind() == Tk.LPAR && !(typeArgs.isNotEmpty() && interrupted())) args = parseArgs()
    val lambdas = ArrayList<Expr>()
    parseLambdaArgs(lambdas)
    done(m, NodeKind.CALL)
    return fin(CallExpr(callee, typeArgs, args, lambdas), s)
}

/** `( [name =] [*] expr, ... )` */
internal fun Parser.parseArgs(): List<Arg> {
    val m = mark()
    val list = ArrayList<Arg>()
    if (kind() != Tk.LPAR) {
        errorPrev("Expecting '('")
        done(m, NodeKind.ARGS)
        return list
    }
    val saved = nlOn
    val savedNoLambda = noLambda
    nlOn = false
    noLambda = false
    advance()
    while (kind() != Tk.RPAR && kind() != Tk.EOF) {
        val before = p
        val am = mark()
        val s = tokStart()
        var name: String? = null
        var spread = false
        if (kind() == Tk.IDENT && la(1) == Tk.EQ) {
            name = tokText()
            advance()
            advance()
        }
        if (kind() == Tk.STAR) {
            spread = true
            advance()
        }
        val e = parseExpression()
        done(am, NodeKind.ARG)
        list.add(fin(Arg(name, spread, e), s))
        if (kind() == Tk.COMMA) {
            advance()
        } else if (kind() != Tk.RPAR) {
            break
        }
        if (p == before) {
            skipError("Unexpected token in argument list")
        }
    }
    if (!expect(Tk.RPAR, "')'")) {
        // recovery: skip the rest of the line
        while (kind() != Tk.RPAR && kind() != Tk.EOF && !nl() && kind() != Tk.RBRACE && kind() != Tk.LBRACE) {
            val em = mark()
            advance()
            done(em, NodeKind.ERROR)
        }
        if (kind() == Tk.RPAR) advance()
    }
    nlOn = saved
    noLambda = savedNoLambda
    done(m, NodeKind.ARGS)
    return list
}

private fun startsExpression(k: Tk): Boolean = when (k) {
    Tk.IDENT, Tk.INT_LIT, Tk.REAL_LIT, Tk.CHAR_LIT, Tk.OPEN_QUOTE, Tk.LPAR, Tk.LBRACE, Tk.LBRACKET, Tk.THIS, Tk.SUPER,
    Tk.IF, Tk.WHEN, Tk.TRY, Tk.OBJECT, Tk.FUN, Tk.FOR, Tk.WHILE, Tk.DO, Tk.RETURN, Tk.THROW, Tk.BREAK, Tk.CONTINUE,
    Tk.PLUS, Tk.MINUS, Tk.PLUSPLUS, Tk.MINUSMINUS, Tk.EXCL, Tk.EXCLEXCL, Tk.AT, Tk.COLONCOLON, Tk.NULL, Tk.TRUE, Tk.FALSE,
    -> true
    else -> false
}

private fun Parser.optionalLabel(): String? {
    if (kind() == Tk.AT && adjacentPrevToCurrent() && adjacent(0) && la(1) == Tk.IDENT) {
        val lm = mark()
        advance()
        val l = tokText()
        advance()
        done(lm, NodeKind.LABEL)
        return l
    }
    return null
}

private fun Parser.parseAtom(): Expr {
    val m = mark()
    val s = tokStart()
    val k = kind()
    when (k) {
        Tk.INT_LIT, Tk.REAL_LIT, Tk.CHAR_LIT, Tk.TRUE, Tk.FALSE, Tk.NULL -> {
            val t = tokText()
            advance()
            done(m, NodeKind.LITERAL)
            val lk = when (k) {
                Tk.INT_LIT -> LitKind.INT
                Tk.REAL_LIT -> LitKind.REAL
                Tk.CHAR_LIT -> LitKind.CHAR
                Tk.TRUE -> LitKind.TRUE
                Tk.FALSE -> LitKind.FALSE
                else -> LitKind.NULL
            }
            return fin(LiteralExpr(lk, t), s)
        }
        Tk.OPEN_QUOTE -> return parseString()
        Tk.IDENT -> {
            val t = tokText()
            advance()
            done(m, NodeKind.NAME)
            return fin(NameExpr(t), s)
        }
        Tk.THIS -> {
            advance()
            val label = optionalLabel()
            done(m, NodeKind.THIS)
            return fin(ThisExpr(label), s)
        }
        Tk.SUPER -> {
            advance()
            var ta: TypeRef? = null
            if (kind() == Tk.LT) {
                val saved = nlOn
                nlOn = false
                advance()
                ta = parseType()
                expect(Tk.GT, "'>'")
                nlOn = saved
            }
            val label = optionalLabel()
            done(m, NodeKind.SUPER)
            return fin(SuperExpr(ta, label), s)
        }
        Tk.LPAR -> {
            val saved = nlOn
            val savedNoLambda = noLambda
            nlOn = false
            noLambda = false
            advance()
            val e = parseExpression()
            expect(Tk.RPAR, "')'")
            nlOn = saved
            noLambda = savedNoLambda
            done(m, NodeKind.PAREN)
            return fin(ParenExpr(e), s)
        }
        Tk.LBRACE -> return parseLambda()
        Tk.LBRACKET -> {
            val saved = nlOn
            nlOn = false
            advance()
            val els = ArrayList<Expr>()
            while (kind() != Tk.RBRACKET && kind() != Tk.EOF) {
                val before = p
                els.add(parseExpression())
                if (kind() == Tk.COMMA) advance() else break
                if (p == before) break
            }
            expect(Tk.RBRACKET, "']'")
            nlOn = saved
            done(m, NodeKind.COLLECTION_LITERAL)
            return fin(CollectionLiteral(els), s)
        }
        Tk.IF -> return parseIf()
        Tk.WHEN -> return parseWhen()
        Tk.TRY -> return parseTry()
        Tk.FOR -> return parseFor()
        Tk.WHILE -> return parseWhile()
        Tk.DO -> return parseDoWhile()
        Tk.FUN -> return parseAnonFun(m, s, null)
        Tk.OBJECT -> return parseObjectLiteral()
        Tk.THROW -> {
            advance()
            var e: Expr? = null
            if (startsExpression(kind())) e = parseExpression() else errorPrev("Expecting an expression")
            done(m, NodeKind.THROW)
            return fin(ThrowExpr(e), s)
        }
        Tk.RETURN -> {
            advance()
            val label = optionalLabel()
            var e: Expr? = null
            if (startsExpression(kind()) && !(nlOn && nl())) e = parseExpression()
            done(m, NodeKind.RETURN)
            return fin(ReturnExpr(label, e), s)
        }
        Tk.BREAK -> {
            advance()
            val label = optionalLabel()
            done(m, NodeKind.BREAK)
            return fin(BreakExpr(label), s)
        }
        Tk.CONTINUE -> {
            advance()
            val label = optionalLabel()
            done(m, NodeKind.CONTINUE)
            return fin(ContinueExpr(label), s)
        }
        Tk.COLONCOLON -> {
            advance()
            var name = "<missing>"
            if (kind() == Tk.IDENT || kind() == Tk.CLASS) {
                name = tokText()
                advance()
            } else {
                errorPrev("Expecting an identifier")
            }
            done(m, NodeKind.CALLABLE_REF)
            return fin(CallableRefExpr(null, name), s)
        }
        else -> {
            errorPrev("Expecting an element")
            done(m, NodeKind.ERROR)
            return fin(ErrorExpr(), s)
        }
    }
}

// ---- strings ----------------------------------------------------------------------------------------------------

private fun Parser.parseString(): Expr {
    val m = mark()
    val s = tokStart()
    val raw = tokEndAt(0) - tokStartAt(0) >= 3 && text[tokEndAt(0) - 1] == '"' && text[tokEndAt(0) - 2] == '"' && text[tokEndAt(0) - 3] == '"'
    advance() // opening quote
    val parts = ArrayList<StrPart>()
    while (true) {
        val k = kind()
        val ps = tokStart()
        if (k == Tk.STR_PART) {
            val t = tokText()
            advance()
            parts.add(fin(StrText(t), ps))
        } else if (k == Tk.STR_ESCAPE) {
            val t = tokText()
            advance()
            parts.add(fin(StrEscape(t), ps))
        } else if (k == Tk.TEMPLATE_SHORT) {
            val em = mark()
            advance()
            var name = "<missing>"
            if (kind() == Tk.IDENT || kind() == Tk.THIS) {
                name = tokText()
                advance()
            }
            done(em, NodeKind.STR_SHORT)
            parts.add(fin(StrShortRef(name), ps))
        } else if (k == Tk.TEMPLATE_LONG_START) {
            val em = mark()
            advance()
            val e = parseExpression()
            if (kind() == Tk.TEMPLATE_LONG_END) {
                advance()
            } else {
                errorPrev("Expecting '}'")
                // skip to the end of the entry
                while (kind() != Tk.TEMPLATE_LONG_END && kind() != Tk.CLOSE_QUOTE && kind() != Tk.EOF) {
                    val xm = mark()
                    advance()
                    done(xm, NodeKind.ERROR)
                }
                if (kind() == Tk.TEMPLATE_LONG_END) advance()
            }
            done(em, NodeKind.STR_LONG)
            parts.add(fin(StrLongExpr(e), ps))
        } else {
            break
        }
    }
    if (kind() == Tk.CLOSE_QUOTE) {
        advance()
    } else {
        errorPrev("Unclosed string literal")
    }
    done(m, NodeKind.STRING)
    return fin(StringExpr(raw, parts), s)
}

// ---- blocks and statements --------------------------------------------------------------------------------------

internal fun Parser.parseBlock(): BlockExpr {
    val m = mark()
    val s = tokStart()
    val stmts = ArrayList<Stmt>()
    if (kind() != Tk.LBRACE) {
        errorPrev("Expecting '{'")
        done(m, NodeKind.BLOCK)
        return fin(BlockExpr(stmts), s)
    }
    val saved = nlOn
    val savedNoLambda = noLambda
    nlOn = true
    noLambda = false
    advance()
    parseStatements(stmts)
    expect(Tk.RBRACE, "'}'")
    nlOn = saved
    noLambda = savedNoLambda
    done(m, NodeKind.BLOCK)
    return fin(BlockExpr(stmts), s)
}

/** statements up to (excluding) `}`; each statement must be ended by a newline, `;` or the closing brace */
internal fun Parser.parseStatements(into: ArrayList<Stmt>) {
    while (kind() != Tk.RBRACE && kind() != Tk.EOF && kind() != Tk.TEMPLATE_LONG_END) {
        if (kind() == Tk.SEMI) {
            advance()
            continue
        }
        val before = p
        val st = parseStatement()
        if (st != null) into.add(st)
        if (p == before) {
            skipError("Unexpected token")
            continue
        }
        val k = kind()
        if (k != Tk.RBRACE && k != Tk.EOF && k != Tk.SEMI && k != Tk.TEMPLATE_LONG_END && !nl() && !prevIsSemi()) {
            error("Unexpected tokens (use ';' to separate expressions on the same line)")
        }
    }
}

internal fun Parser.parseStatement(): Stmt? {
    val d = tryParseDeclaration(CTX_LOCAL)
    if (d != null) return d
    if (kind() == Tk.AT) {
        // annotations on their own line apply to the whole following statement (`@Suppress("X")\n a = b`);
        // on the same line they bind to the first operand
        val sn = snap()
        val m = mark()
        val s = tokStart()
        val anns = ArrayList<AnnotationEntry>()
        while (kind() == Tk.AT) {
            val before = p
            parseAnnotationInto(anns, false)
            if (p == before) break
        }
        if (nl() && anns.isNotEmpty()) {
            val e = parseExpression()
            done(m, NodeKind.ANNOTATED)
            return fin(AnnotatedExpr(anns, e), s)
        }
        restore(sn)
    }
    return parseExpression()
}

/** body of if/while/for/when branches: a block or a single statement-expression, or null for `;` */
private fun Parser.parseBody(): Expr? {
    if (kind() == Tk.SEMI) return null
    if (kind() == Tk.LBRACE) return if (lambdaHasArrow()) parseLambda() else parseBlock()
    return parseExpression()
}

/** at `{`: does a lambda parameter list followed by `->` come next? ({ x -> ... } is a lambda even in a branch) */
private fun Parser.lambdaHasArrow(): Boolean {
    val sn = snap()
    advance()
    var ok = false
    if (kind() == Tk.ARROW) {
        ok = true
    } else if (kind() == Tk.IDENT || kind() == Tk.COLON || kind() == Tk.LPAR) {
        val ps = tryParseLambdaParams()
        ok = ps != null && kind() == Tk.ARROW
    }
    restore(sn)
    return ok
}

// ---- control flow -----------------------------------------------------------------------------------------------

private fun Parser.parseParenCondition(): Expr {
    val saved = nlOn
    nlOn = false
    var e: Expr
    if (kind() == Tk.LPAR) {
        advance()
        e = parseExpression()
        expect(Tk.RPAR, "')'")
    } else {
        errorPrev("Expecting '('")
        e = fin(ErrorExpr(), tokStart())
    }
    nlOn = saved
    return e
}

private fun Parser.parseIf(): Expr {
    val m = mark()
    val s = tokStart()
    advance() // if
    val cond = parseParenCondition()
    val then = if (kind() == Tk.ELSE) null else parseBody()
    var els: Expr? = null
    val sn = snap()
    if (kind() == Tk.SEMI && la(1) == Tk.ELSE && la(2) != Tk.ARROW) advance()
    if (kind() == Tk.ELSE && la(1) != Tk.ARROW) {
        advance()
        els = parseBody()
    } else {
        restore(sn)
    }
    done(m, NodeKind.IF)
    return fin(IfExpr(cond, then, els), s)
}

private fun Parser.parseWhile(): Expr {
    val m = mark()
    val s = tokStart()
    advance()
    val cond = parseParenCondition()
    val body = parseBody()
    done(m, NodeKind.WHILE)
    return fin(WhileExpr(cond, body), s)
}

private fun Parser.parseDoWhile(): Expr {
    val m = mark()
    val s = tokStart()
    advance() // do
    val body = parseBody()
    var cond: Expr = fin(ErrorExpr(), tokStart())
    if (kind() == Tk.WHILE) {
        advance()
        cond = parseParenCondition()
    } else {
        errorPrev("Expecting 'while'")
    }
    done(m, NodeKind.DO_WHILE)
    return fin(DoWhileExpr(body, cond), s)
}

private fun Parser.parseFor(): Expr {
    val m = mark()
    val s = tokStart()
    advance() // for
    val saved = nlOn
    nlOn = false
    var entries: List<DestructEntry> = emptyList()
    var destructuring = false
    var iterable: Expr = fin(ErrorExpr(), tokStart())
    if (kind() == Tk.LPAR) {
        advance()
        val em = mark()
        val es = tokStart()
        if (kind() == Tk.LPAR) {
            destructuring = true
            entries = parseDestructEntries()
        } else {
            val mods = parseModifierList()
            if (kind() == Tk.VAL || kind() == Tk.VAR) advance() // accepted by the reference parser, rejected later
            val name = parseName("loop variable")
            var t: TypeRef? = null
            if (kind() == Tk.COLON) {
                advance()
                t = parseType()
            }
            done(em, NodeKind.DESTRUCT_ENTRY)
            entries = listOf(fin(DestructEntry(mods, false, name, t), es))
        }
        if (kind() == Tk.IN) {
            advance()
            iterable = parseExpression()
        } else {
            errorPrev("Expecting 'in'")
        }
        expect(Tk.RPAR, "')'")
    } else {
        errorPrev("Expecting '('")
    }
    nlOn = saved
    val body = parseBody()
    done(m, NodeKind.FOR)
    return fin(ForExpr(entries, destructuring, iterable, body), s)
}

private fun Parser.parseTry(): Expr {
    val m = mark()
    val s = tokStart()
    advance() // try
    val body = parseBlock()
    val catches = ArrayList<CatchClause>()
    var finallyBlock: BlockExpr? = null
    while (isIdent("catch")) {
        val cm = mark()
        val cs = tokStart()
        advance()
        val saved = nlOn
        nlOn = false
        var param = Param(null, null, "<missing>", null, null)
        if (kind() == Tk.LPAR) {
            advance()
            param = parseParam(false)
            if (kind() == Tk.COMMA) advance()
            expect(Tk.RPAR, "')'")
        } else {
            errorPrev("Expecting '('")
        }
        nlOn = saved
        val b = parseBlock()
        done(cm, NodeKind.CATCH)
        catches.add(fin(CatchClause(param, b), cs))
    }
    if (isIdent("finally")) {
        val fm = mark()
        advance()
        finallyBlock = parseBlock()
        done(fm, NodeKind.FINALLY)
    }
    if (catches.isEmpty() && finallyBlock == null) errorPrev("Expecting 'catch' or 'finally'")
    done(m, NodeKind.TRY)
    return fin(TryExpr(body, catches, finallyBlock), s)
}

private fun Parser.parseWhen(): Expr {
    val m = mark()
    val s = tokStart()
    advance() // when
    var subject: WhenSubject? = null
    if (kind() == Tk.LPAR) {
        val sm = mark()
        val ss = tokStart()
        val saved = nlOn
        nlOn = false
        advance()
        var valName: String? = null
        var valType: TypeRef? = null
        if (kind() == Tk.VAL) {
            advance()
            valName = parseName("variable name")
            if (kind() == Tk.COLON) {
                advance()
                valType = parseType()
            }
            expect(Tk.EQ, "'='")
        }
        val e = parseExpression()
        expect(Tk.RPAR, "')'")
        nlOn = saved
        done(sm, NodeKind.WHEN_SUBJECT)
        subject = fin(WhenSubject(valName, valType, e), ss)
    }
    val entries = ArrayList<WhenEntry>()
    if (kind() == Tk.LBRACE) {
        val saved = nlOn
        nlOn = true
        advance()
        while (kind() != Tk.RBRACE && kind() != Tk.EOF) {
            if (kind() == Tk.SEMI) {
                advance()
                continue
            }
            val before = p
            entries.add(parseWhenEntry())
            if (p == before) skipError("Unexpected token in when")
        }
        expect(Tk.RBRACE, "'}'")
        nlOn = saved
    } else {
        errorPrev("Expecting '{'")
    }
    done(m, NodeKind.WHEN)
    return fin(WhenExpr(subject, entries), s)
}

private fun Parser.parseWhenEntry(): WhenEntry {
    val m = mark()
    val s = tokStart()
    val conds = ArrayList<WhenCond>()
    var isElse = false
    var guard: Expr? = null
    if (kind() == Tk.ELSE) {
        isElse = true
        advance()
    } else {
        val savedNl = nlOn
        nlOn = false // conditions may span lines
        while (true) {
            val before = p
            conds.add(parseWhenCond())
            if (kind() == Tk.COMMA) {
                advance()
                if (kind() == Tk.ARROW) break
            } else {
                break
            }
            if (p == before) break
        }
        if (kind() == Tk.IF) {
            advance()
            guard = parseExpression()
        }
        nlOn = savedNl
    }
    var body: Expr = fin(ErrorExpr(), tokStart())
    if (kind() == Tk.ARROW) {
        advance()
        val b = parseBody()
        if (b != null) body = b
    } else {
        errorPrev("Expecting '->'")
    }
    if (kind() == Tk.SEMI) advance()
    done(m, NodeKind.WHEN_ENTRY)
    return fin(WhenEntry(conds, isElse, guard, body), s)
}

private fun Parser.parseWhenCond(): WhenCond {
    val m = mark()
    val s = tokStart()
    val k = kind()
    if (k == Tk.IN || k == Tk.NOT_IN) {
        advance()
        val e = parseExpression()
        done(m, NodeKind.WHEN_COND_IN)
        return fin(WhenInCond(k == Tk.NOT_IN, e), s)
    }
    if (k == Tk.IS || k == Tk.NOT_IS) {
        advance()
        val t = parseType()
        done(m, NodeKind.WHEN_COND_IS)
        return fin(WhenIsCond(k == Tk.NOT_IS, t), s)
    }
    val e = parseExpression()
    done(m, NodeKind.WHEN_COND_EXPR)
    return fin(WhenExprCond(e), s)
}

// ---- lambdas, anonymous functions, object literals ---------------------------------------------------------------

internal fun Parser.parseLambda(): Expr {
    val m = mark()
    val s = tokStart()
    val saved = nlOn
    val savedNoLambda = noLambda
    nlOn = true
    noLambda = false
    advance() // {
    var params: List<LambdaParam> = emptyList()
    var hasArrow = false
    if (kind() == Tk.ARROW) {
        val pm = mark()
        done(pm, NodeKind.LAMBDA_PARAMS)
        advance()
        hasArrow = true
    } else {
        val sn = snap()
        val pm = mark()
        val ps = tryParseLambdaParams()
        if (ps != null && kind() == Tk.ARROW) {
            done(pm, NodeKind.LAMBDA_PARAMS)
            advance()
            params = ps
            hasArrow = true
        } else {
            restore(sn)
        }
    }
    val body = ArrayList<Stmt>()
    parseStatements(body)
    expect(Tk.RBRACE, "'}'")
    nlOn = saved
    noLambda = savedNoLambda
    done(m, NodeKind.LAMBDA)
    return fin(LambdaExpr(params, hasArrow, body), s)
}

/** `a, (b, c): T, d: U` or null if this is not a lambda parameter list */
private fun Parser.tryParseLambdaParams(): List<LambdaParam>? {
    val list = ArrayList<LambdaParam>()
    while (true) {
        val m = mark()
        val s = tokStart()
        val mods = parseModifierList()
        var names: List<DestructEntry>? = null
        var name: String? = null
        if (kind() == Tk.IDENT) {
            name = tokText()
            advance()
        } else if (kind() == Tk.LPAR) {
            val errs = errors.size
            names = parseDestructEntries()
            if (errors.size != errs) return null
        } else {
            return null
        }
        var t: TypeRef? = null
        if (kind() == Tk.COLON) {
            advance()
            val errs = errors.size
            t = parseType()
            if (errors.size != errs) return null
        }
        done(m, NodeKind.LAMBDA_PARAM)
        list.add(fin(LambdaParam(mods, names, name, t), s))
        if (kind() == Tk.COMMA) {
            advance()
            if (kind() == Tk.ARROW) break
        } else {
            break
        }
    }
    return list
}

private fun Parser.parseAnonFun(m: Int, s: Int, mods: Modifiers?): Expr {
    advance() // fun
    var receiver: TypeRef? = null
    var fname: String? = null
    if (kind() == Tk.IDENT && la(1) == Tk.LPAR) {
        // a named function in expression position: the reference parser accepts it
        fname = tokText()
        advance()
    } else if (kind() != Tk.LPAR) {
        val sn = snap()
        val t = parseType(true)
        if (kind() == Tk.DOT) {
            advance()
            receiver = t
            if (kind() == Tk.IDENT && la(1) == Tk.LPAR) {
                fname = tokText()
                advance()
            }
        } else {
            restore(sn)
        }
    }
    val params = parseParams(true)
    var ret: TypeRef? = null
    if (kind() == Tk.COLON) {
        advance()
        ret = parseType()
    }
    var body: Expr? = null
    var exprBody = false
    if (kind() == Tk.LBRACE) {
        body = parseBlock()
    } else if (kind() == Tk.EQ) {
        advance()
        exprBody = true
        body = parseExpression()
    }
    done(m, NodeKind.ANON_FUN)
    val fn = fin(FunDecl(mods, emptyList(), receiver, fname, params, ret, emptyList(), body, exprBody), s)
    return fin(AnonFunExpr(fn), s)
}

private fun Parser.parseObjectLiteral(): Expr {
    val m = mark()
    val s = tokStart()
    advance() // object
    var supers: List<SuperEntry> = emptyList()
    if (kind() == Tk.COLON) {
        advance()
        supers = parseSuperEntries()
    }
    val members = ArrayList<Decl>()
    if (kind() == Tk.LBRACE) {
        val bm = mark()
        val saved = nlOn
        nlOn = true
        advance()
        parseMembers(members)
        expect(Tk.RBRACE, "'}'")
        nlOn = saved
        done(bm, NodeKind.CLASS_BODY)
    }
    done(m, NodeKind.OBJECT_LITERAL)
    return fin(ObjectLiteral(supers, members), s)
}
