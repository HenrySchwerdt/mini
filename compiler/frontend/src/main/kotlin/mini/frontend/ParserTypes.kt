package mini.frontend

// Type grammar: userType, nullable, parenthesized, function types (receiver, context, suspend), dynamic, `T & Any`.

/**
 * Parses a type. With [recv] the type is a receiver type of a declaration (`fun A.B.f()`): a trailing `.name` that is
 * not followed by `.` or `<` is left for the declaration.
 */
internal fun Parser.parseType(recv: Boolean): TypeRef {
    val m = mark()
    val s = tokStart()
    // type modifiers: annotations, suspend, context(...)
    val mm = mark()
    val ms = tokStart()
    val items = ArrayList<ModItem>()
    var ctxTypes: List<TypeRef> = emptyList()
    while (true) {
        if (kind() == Tk.AT) {
            val anns = ArrayList<AnnotationEntry>()
            val before = p
            parseAnnotationInto(anns, true)
            for (a in anns) items.add(AnnotationMod(a))
            if (p == before) break
        } else if (isIdent("suspend") && (la(1) == Tk.LPAR || la(1) == Tk.IDENT || la(1) == Tk.AT)) {
            val ks = tokStart()
            advance()
            items.add(fin(KeywordMod("suspend"), ks))
        } else if (isIdent("context") && la(1) == Tk.LPAR && ctxTypes.isEmpty()) {
            ctxTypes = parseContextTypes()
        } else {
            break
        }
    }
    var mods: Modifiers? = null
    if (items.isNotEmpty()) {
        done(mm, NodeKind.MODIFIERS)
        mods = fin(Modifiers(items, emptyList()), ms)
    }
    var t: TypeRef
    val k = kind()
    if (k == Tk.LPAR) {
        t = parseParenOrFunctionType(ctxTypes, null)
    } else if (k == Tk.IDENT) {
        if (isIdent("dynamic") && la(1) != Tk.DOT && la(1) != Tk.LT) {
            advance()
            done(m, NodeKind.TYPE_DYNAMIC)
            t = fin(DynamicType(), s)
        } else {
            t = parseUserType(recv, false)
        }
    } else {
        errorPrev("Expecting a type")
        t = fin(ErrorType(), s)
    }
    t = parseTypeSuffixes(m, s, t, ctxTypes, recv)
    if (kind() == Tk.AMP && la(1) != Tk.AMP) {
        advance()
        val r = parseType(false)
        done(m, NodeKind.TYPE_INTERSECTION)
        t = fin(IntersectionType(t, r), s)
    }
    t.mods = mods
    return t
}

internal fun Parser.parseType(): TypeRef = parseType(false)

/** `?` suffixes and the `A.(B) -> C` form (receiver function type) after an already parsed type */
private fun Parser.parseTypeSuffixes(m: Int, s: Int, first: TypeRef, ctxTypes: List<TypeRef>, recv: Boolean): TypeRef {
    var t = first
    while (true) {
        if (kind() == Tk.QUEST && !atElvis() && !(nlOn && nl())) {
            advance()
            done(m, NodeKind.TYPE_NULLABLE)
            t = fin(NullableType(t), s)
        } else if (!recv && kind() == Tk.DOT && la(1) == Tk.LPAR) {
            advance()
            t = parseParenOrFunctionType(ctxTypes, t)
            // parseParenOrFunctionType wrapped from its own mark; re-wrap to include the receiver
            t = fin(t, s)
        } else {
            break
        }
    }
    return t
}

private fun Parser.parseContextTypes(): List<TypeRef> {
    advance() // context
    val saved = nlOn
    nlOn = false
    advance() // (
    val list = ArrayList<TypeRef>()
    while (kind() != Tk.RPAR && kind() != Tk.EOF) {
        val before = p
        list.add(parseType())
        if (kind() == Tk.COMMA) advance() else break
        if (p == before) break
    }
    expect(Tk.RPAR, "')'")
    nlOn = saved
    return list
}

/** at `(`: `(A, b: B) -> R` or a parenthesized type `(A)`; [receiver] is set for `A.(B) -> R` */
private fun Parser.parseParenOrFunctionType(ctxTypes: List<TypeRef>, receiver: TypeRef?): TypeRef {
    val m = mark()
    val s = tokStart()
    val saved = nlOn
    nlOn = false
    advance() // (
    val params = ArrayList<FnTypeParam>()
    while (kind() != Tk.RPAR && kind() != Tk.EOF) {
        val before = p
        val pm = mark()
        val ps = tokStart()
        var name: String? = null
        if (kind() == Tk.IDENT && la(1) == Tk.COLON) {
            name = tokText()
            advance()
            advance()
        }
        val t = parseType()
        done(pm, NodeKind.TYPE_FN_PARAM)
        params.add(fin(FnTypeParam(name, t), ps))
        if (kind() == Tk.COMMA) advance() else break
        if (p == before) break
    }
    expect(Tk.RPAR, "')'")
    nlOn = saved
    if (kind() == Tk.ARROW) {
        advance()
        val ret = parseType()
        done(m, NodeKind.TYPE_FUNCTION)
        return fin(FunctionType(ctxTypes, receiver, params, ret), s)
    }
    if (receiver == null && params.size == 1 && params[0].name == null) {
        done(m, NodeKind.TYPE_PAREN)
        return fin(ParenType(params[0].type), s)
    }
    errorPrev("Expecting '->'")
    done(m, NodeKind.TYPE_FUNCTION)
    return fin(FunctionType(ctxTypes, receiver, params, ErrorType()), s)
}

/** `a.b.C<T>.D`; [annotation]: parsing the name of an annotation (no `?` handling, arguments follow) */
internal fun Parser.parseUserType(recv: Boolean, annotation: Boolean): UserType {
    val m = mark()
    val s = tokStart()
    val segs = ArrayList<TypeSegment>()
    while (true) {
        val sm = mark()
        val ss = tokStart()
        if (kind() != Tk.IDENT) {
            errorPrev("Expecting a type name")
            break
        }
        val name = tokText()
        advance()
        var args: List<TypeArg> = emptyList()
        if (kind() == Tk.LT) args = parseTypeArgs()
        done(sm, NodeKind.TYPE_SEGMENT)
        segs.add(fin(TypeSegment(name, args), ss))
        if (kind() == Tk.DOT && la(1) == Tk.IDENT) {
            if (recv) {
                val after = la(2)
                if (after != Tk.DOT && after != Tk.LT) break
            }
            advance()
        } else {
            break
        }
    }
    done(m, NodeKind.TYPE_USER)
    return fin(UserType(segs), s)
}

internal fun Parser.parseTypeArgs(): List<TypeArg> {
    val m = mark()
    val saved = nlOn
    nlOn = false
    advance() // <
    val list = ArrayList<TypeArg>()
    while (kind() != Tk.GT && kind() != Tk.EOF) {
        val before = p
        val am = mark()
        val s = tokStart()
        if (kind() == Tk.STAR) {
            advance()
            done(am, NodeKind.TYPE_ARG)
            list.add(fin(TypeArg(true, null, null), s))
        } else {
            var variance: String? = null
            // annotations may precede the variance
            if (kind() == Tk.IN) {
                variance = "in"
                advance()
            } else if (isIdent("out") && la(1) != Tk.COMMA && la(1) != Tk.GT && la(1) != Tk.DOT && la(1) != Tk.LT) {
                variance = "out"
                advance()
            }
            val t = parseType()
            done(am, NodeKind.TYPE_ARG)
            list.add(fin(TypeArg(false, variance, t), s))
        }
        if (kind() == Tk.COMMA) advance() else break
        if (p == before) break
    }
    expect(Tk.GT, "'>'")
    nlOn = saved
    done(m, NodeKind.TYPE_ARGS)
    return list
}

/**
 * Speculative type argument list in an expression (`foo<Int>(x)`, `Foo<A, B>::bar`): returns the arguments, or null
 * (nothing consumed) when what follows `<` is not a type argument list followed by something that continues an
 * expression.
 */
internal fun Parser.tryParseTypeArgsInExpr(): List<TypeArg>? {
    val sn = snap()
    val errs = errors.size
    // quick check for tokens that can never appear in a type argument list
    val args = parseTypeArgs()
    // like the reference parser: a well-formed type argument list makes this a call suffix, whatever follows
    // (`>=` is one token for the reference lexer, so a closing `>` directly followed by `=` is no type argument list)
    if (errors.size == errs && !(kind() == Tk.EQ && adjacentPrevToCurrent())) return args
    restore(sn)
    return null
}
