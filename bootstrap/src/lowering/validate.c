#include "project_internal.h"
#include <string.h>
#include <stdlib.h>

bool mini_pstorage(Context *c, Unit *unit, Token at, Type *type, bool allow_void) {
    if (!type)
        return false;
    if (type->kind == TY_VOID && !allow_void)
        mini_perror(c, unit, at, "void is not a storage type");
    if (type->kind == TY_NOMINAL && type->ref.decl->kind == MINI_DECL_STRUCT) {
        if (type->ref.decl->as.struct_decl.is_interface ||
            (type->ref.decl->flags & MINI_ABSTRACT) || type->ref.decl->as.struct_decl.is_annotation)
            mini_perror(c, unit, at,
                        "abstract/interface/annotation types cannot be stored by value");
    }
    if (type->kind == TY_ARRAY)
        mini_pstorage(c, unit, at, type->element, false);
    if (type->kind == TY_OPTIONAL || type->kind == TY_RESULT)
        for (size_t i = 0; i < type->arg_count; i++)
            mini_pstorage(c, unit, at, type->args[i], false);
    return !c->result.message;
}
static bool text(Context *c, Unit *u, Token name, const char *value) {
    return !strcmp(mini_pname(c, u, name), value);
}
static bool parameter(Context *c, Ref owner, Ref method, const char *name) {
    Ref refs[] = {owner, method};
    for (size_t r = 0; r < 2; r++)
        if (refs[r].decl)
            for (size_t i = 0; i < refs[r].decl->generic_count; i++)
                if (!strcmp(name, mini_pname(c, refs[r].unit, refs[r].decl->generics[i].name)))
                    return true;
    return false;
}
static void type_syntax(Context *c, Ref owner, Ref method, const MiniType *type, int exposure,
                        bool allow_void, size_t depth) {
    if (!type || c->result.message)
        return;
    Unit *u = method.decl ? method.unit : owner.unit;
    if (depth > 128) {
        mini_perror(c, u, (Token){.start = type->start}, "type nesting limit exceeded");
        return;
    }
    if (type->kind == MINI_TYPE_FUNCTION) {
        for (size_t i = 0; i < type->arg_count; i++)
            type_syntax(c, owner, method, type->args[i], exposure, false, depth + 1);
        type_syntax(c, owner, method, type->as.function.result, exposure, true, depth + 1);
        return;
    }
    if (type->kind == MINI_TYPE_POINTER) {
        type_syntax(c, owner, method, type->as.pointer.element, exposure, false, depth + 1);
        return;
    }
    if (type->kind == MINI_TYPE_ARRAY) {
        type_syntax(c, owner, method, type->as.array.element, exposure, false, depth + 1);
        return;
    }
    char *name = mini_pname(c, u, type->as.name);
    size_t expected = 0;
    if (!strcmp(name, "void")) {
        if (!allow_void)
            mini_perror(c, u, type->as.name, "void is only a return type");
    } else if (!strcmp(name, "i32") || !strcmp(name, "u8") || !strcmp(name, "usize") ||
               !strcmp(name, "isize") || !strcmp(name, "bool")) {
    } else if (!strcmp(name, "Self")) {
        if (!owner.decl)
            mini_perror(c, u, type->as.name, "Self requires a class context");
    } else if (parameter(c, owner, method, name)) {
    } else if (!strcmp(name, "Optional"))
        expected = 1;
    else if (!strcmp(name, "Result"))
        expected = 2;
    else {
        Ref ref = mini_plookup(c, u, name, type->as.name);
        if (!ref.decl)
            return;
        expected = ref.decl->generic_count;
        if (ref.decl->kind == MINI_DECL_FUNCTION ||
            (ref.decl->kind == MINI_DECL_STRUCT && ref.decl->as.struct_decl.is_annotation))
            mini_perror(c, u, type->as.name, "declaration is not a runtime type");
        unsigned flags = ref.decl->flags;
        if ((exposure == 2 && (flags & (MINI_PRIVATE | MINI_INTERNAL))) ||
            (exposure == 1 && (flags & MINI_PRIVATE)))
            mini_perror(c, u, type->as.name, "visible API exposes a less-visible type");
    }
    if (type->arg_count != expected)
        mini_perror(c, u, type->as.name, "incorrect generic type argument count");
    for (size_t i = 0; i < type->arg_count; i++)
        type_syntax(c, owner, method, type->args[i], exposure, false, depth + 1);
}
static void generics(Context *c, Ref owner, Ref ref, int exposure) {
    for (size_t i = 0; i < ref.decl->generic_count; i++) {
        MiniGeneric *g = &ref.decl->generics[i];
        for (size_t j = 0; j < i; j++)
            if (text(c, ref.unit, g->name, mini_pname(c, ref.unit, ref.decl->generics[j].name)))
                mini_perror(c, ref.unit, g->name, "duplicate generic parameter");
        if (g->bound) {
            type_syntax(c, owner, ref, g->bound, exposure, false, 0);
            if (g->bound->kind != MINI_TYPE_NAMED) {
                mini_perror(c, ref.unit, g->name, "generic bound must name a class or interface");
                continue;
            }
            Ref bound = mini_plookup(c, ref.unit, mini_pname(c, ref.unit, g->bound->as.name),
                                     g->bound->as.name);
            if (bound.decl && (bound.decl->kind != MINI_DECL_STRUCT ||
                               (!bound.decl->as.struct_decl.is_interface &&
                                !bound.decl->as.struct_decl.is_class)))
                mini_perror(c, ref.unit, g->name, "generic bound must name a class or interface");
        }
    }
}
static int visibility(unsigned flags, int owner) {
    if (!owner || (flags & MINI_PRIVATE))
        return 0;
    if (owner == 1 || (flags & MINI_INTERNAL))
        return 1;
    return 2;
}
static void modifiers(Context *c, Ref ref, bool member) {
    unsigned f = ref.decl->flags,
             v = f & (MINI_PRIVATE | MINI_PUBLIC | MINI_INTERNAL | MINI_PROTECTED);
    if (v && (v & (v - 1)))
        mini_perror(c, ref.unit, ref.decl->name, "conflicting visibility modifiers");
    if (!member && (f & (MINI_STATIC | MINI_OVERRIDE | MINI_PROTECTED)))
        mini_perror(c, ref.unit, ref.decl->name, "invalid top-level modifier");
    if ((f & MINI_FINAL) && (f & (MINI_OPEN | MINI_ABSTRACT)))
        mini_perror(c, ref.unit, ref.decl->name, "conflicting class/method modifiers");
    if ((f & MINI_PRIVATE) && (f & (MINI_OPEN | MINI_ABSTRACT | MINI_OVERRIDE)))
        mini_perror(c, ref.unit, ref.decl->name, "private methods cannot be virtual");
}
/* Resolve names in unused generic bodies too. Type-dependent operations are
   checked on concrete instantiation, like templates, without emitting erased code. */
typedef struct {
    Context *c;
    Ref owner, method;
    char **names;
    size_t count;
} Names;
static bool known(Names *n, const char *name) {
    for (size_t i = n->count; i > 0; i--)
        if (!strcmp(name, n->names[i - 1]))
            return true;
    return false;
}
static void add(Names *n, Token token) {
    char *name = mini_pname(n->c, n->method.unit, token);
    mini_pappend(n->c, (void **)&n->names, &n->count, sizeof(name), &name);
}
static void expression_names(Names *n, const MiniExpr *e, bool callee);
static void statement_names(Names *n, const MiniStmt *s);
static void pattern_names(Names *n, const MiniPattern *p) {
    if (!p)
        return;
    if (p->payload)
        pattern_names(n, p->payload);
    else if (p->name.kind == TOKEN_IDENTIFIER) {
        char *s = mini_pname(n->c, n->method.unit, p->name);
        if (!strchr(s, '.') && strcmp(s, "None") && strcmp(s, "Some") && strcmp(s, "Ok") &&
            strcmp(s, "Err"))
            add(n, p->name);
    }
}
static void expression_names(Names *n, const MiniExpr *e, bool callee) {
    if (!e || n->c->result.message)
        return;
    Context *c = n->c;
    Unit *u = n->method.unit;
    for (size_t i = 0; i < e->type_arg_count; i++)
        type_syntax(c, n->owner, n->method, e->type_args[i], 0, false, 0);
    switch (e->kind) {
    case MINI_EXPR_IDENTIFIER: {
        char *name = mini_pname(c, u, e->as.identifier);
        if (!known(n, name) && !callee && strcmp(name, "super"))
            mini_perror(c, u, e->as.identifier, "unknown variable '%s'", name);
        break;
    }
    case MINI_EXPR_UNARY:
        expression_names(n, e->as.unary.operand, false);
        break;
    case MINI_EXPR_BINARY:
        expression_names(n, e->as.binary.left, false);
        expression_names(n, e->as.binary.right, false);
        break;
    case MINI_EXPR_ASSIGN:
        expression_names(n, e->as.assign.target, false);
        expression_names(n, e->as.assign.value, false);
        break;
    case MINI_EXPR_CALL:
        expression_names(n, e->as.call.callee, true);
        for (size_t i = 0; i < e->as.call.args.count; i++)
            expression_names(n, e->as.call.args.items[i], false);
        break;
    case MINI_EXPR_MEMBER: {
        const MiniExpr *root = e;
        while (root->kind == MINI_EXPR_MEMBER)
            root = root->as.member.target;
        if (root->kind == MINI_EXPR_IDENTIFIER &&
            !known(n, mini_pname(c, u, root->as.identifier))) {
            char *name = mini_pname(c, u, root->as.identifier);
            if (strcmp(name, "super") && strcmp(name, "heap") && strcmp(name, "runtime") &&
                strcmp(name, "Optional") && strcmp(name, "Result")) {
                /* Qualified package/type paths are resolved during instantiation. */
            }
        } else
            expression_names(n, e->as.member.target, false);
        break;
    }
    case MINI_EXPR_INDEX:
        expression_names(n, e->as.index.target, false);
        expression_names(n, e->as.index.index, false);
        break;
    case MINI_EXPR_LAMBDA: {
        size_t previous = n->count;
        for (size_t i = 0; i < e->as.lambda.param_count; i++) {
            MiniLambdaParameter *p = &e->as.lambda.params[i];
            if (p->type)
                type_syntax(c, n->owner, n->method, p->type, 0, false, 0);
            add(n, p->name);
        }
        if (!e->as.lambda.explicit_params) {
            char *it = "it";
            mini_pappend(c, (void **)&n->names, &n->count, sizeof(it), &it);
        }
        statement_names(n, e->as.lambda.body);
        n->count = previous;
        break;
    }
    case MINI_EXPR_STRUCT: {
        MiniType t = {.kind = MINI_TYPE_NAMED,
                      .as.name = e->as.struct_literal.type_name,
                      .args = e->type_args,
                      .arg_count = e->type_arg_count};
        type_syntax(c, n->owner, n->method, &t, 0, false, 0);
        for (size_t i = 0; i < e->as.struct_literal.field_count; i++)
            expression_names(n, e->as.struct_literal.fields[i].value, false);
        break;
    }
    case MINI_EXPR_MATCH:
        expression_names(n, e->as.match.subject, false);
        for (size_t i = 0; i < e->as.match.arm_count; i++) {
            size_t old = n->count;
            pattern_names(n, e->as.match.arms[i].tree);
            expression_names(n, e->as.match.arms[i].value, false);
            n->count = old;
        }
        break;
    case MINI_EXPR_CAST:
    case MINI_EXPR_SIZEOF:
        type_syntax(c, n->owner, n->method, e->as.builtin.type, 0, false, 0);
        expression_names(n, e->as.builtin.value, false);
        break;
    default:
        break;
    }
}
static void statement_names(Names *n, const MiniStmt *s) {
    if (!s || n->c->result.message)
        return;
    switch (s->kind) {
    case MINI_STMT_BLOCK: {
        size_t old = n->count;
        for (size_t i = 0; i < s->as.block.count; i++)
            statement_names(n, s->as.block.items[i]);
        n->count = old;
        break;
    }
    case MINI_STMT_LET:
        type_syntax(n->c, n->owner, n->method, s->as.let.type, 0, false, 0);
        expression_names(n, s->as.let.value, false);
        add(n, s->as.let.name);
        break;
    case MINI_STMT_RETURN:
        expression_names(n, s->as.ret.value, false);
        break;
    case MINI_STMT_EXPR:
        expression_names(n, s->as.expr, false);
        break;
    case MINI_STMT_IF:
        expression_names(n, s->as.if_stmt.condition, false);
        statement_names(n, s->as.if_stmt.then_branch);
        statement_names(n, s->as.if_stmt.else_branch);
        break;
    case MINI_STMT_WHILE:
        expression_names(n, s->as.while_stmt.condition, false);
        statement_names(n, s->as.while_stmt.body);
        break;
    }
}
bool mini_pvalidate(Context *c) {
    for (size_t i = 0; i < c->unit_count && !c->result.message; i++) {
        Unit *u = &c->units[i];
        for (size_t j = 0; j < u->ast->count && !c->result.message; j++) {
            const MiniDecl *d = u->ast->items[j];
            Ref owner = {u, d};
            modifiers(c, owner, false);
            char *name = mini_pname(c, u, d->name);
            if (!strcmp(name, "Optional") || !strcmp(name, "Result") || !strcmp(name, "i32") ||
                !strcmp(name, "u8") || !strcmp(name, "usize") || !strcmp(name, "isize") ||
                !strcmp(name, "void") || !strcmp(name, "bool"))
                mini_perror(c, u, d->name, "reserved native type name");
            if (d->kind == MINI_DECL_FUNCTION) {
                mini_perror(c, u, d->name, "functions must be declared inside a class");
                break;
            }
            mini_pannotations(c, owner, d->annotations, d->annotation_count);
            if (d->kind == MINI_DECL_ENUM)
                continue;
            int exposed = visibility(d->flags, 2);
            generics(c, owner, owner, exposed);
            mini_pannotations(c, owner, d->annotations, d->annotation_count);
            if (d->as.struct_decl.is_annotation &&
                (d->generic_count || d->as.struct_decl.super_count ||
                 d->as.struct_decl.method_count))
                mini_perror(c, u, d->name, "annotation classes can only declare primitive fields");
            if (!d->as.struct_decl.is_class && !d->as.struct_decl.is_interface &&
                !d->as.struct_decl.is_annotation &&
                (d->as.struct_decl.method_count || d->as.struct_decl.super_count))
                mini_perror(c, u, d->name, "methods and supertypes require a class or interface");
            for (size_t k = 0; k < d->as.struct_decl.super_count; k++)
                type_syntax(c, owner, (Ref){0}, d->as.struct_decl.supers[k], exposed, false, 0);
            for (size_t k = 0; k < d->as.struct_decl.field_count; k++) {
                const MiniParameter *f = &d->as.struct_decl.fields[k];
                type_syntax(c, owner, (Ref){0}, f->type, visibility(f->flags, exposed), false, 0);
                if (f->flags &
                    ~(MINI_PUBLIC | MINI_PRIVATE | MINI_PROTECTED | MINI_INTERNAL | MINI_IMMUTABLE))
                    mini_perror(c, u, f->name, "unsupported field modifier");
                unsigned v =
                    f->flags & (MINI_PUBLIC | MINI_PRIVATE | MINI_PROTECTED | MINI_INTERNAL);
                if (v && (v & (v - 1)))
                    mini_perror(c, u, f->name, "conflicting field visibility");
                mini_pannotations(c, owner, f->annotations, f->annotation_count);
                for (size_t a = 0; a < k; a++)
                    if (text(c, u, f->name, mini_pname(c, u, d->as.struct_decl.fields[a].name)))
                        mini_perror(c, u, f->name, "duplicate field");
            }
            for (size_t k = 0; k < d->as.struct_decl.method_count && !c->result.message; k++) {
                const MiniDecl *m = d->as.struct_decl.methods[k];
                Ref ref = {u, m};
                if (m->as.function.is_extern && !(m->flags & MINI_STATIC))
                    mini_perror(c, u, m->name, "extern methods must be static");
                int api = visibility(m->flags, exposed);
                modifiers(c, ref, true);
                generics(c, owner, ref, api);
                mini_pannotations(c, ref, m->annotations, m->annotation_count);
                for (size_t a = 0; a < m->as.function.param_count; a++) {
                    type_syntax(c, owner, ref, m->as.function.params[a].type, api, false, 0);
                    if (text(c, u, m->as.function.params[a].name, "this"))
                        mini_perror(c, u, m->name, "this is an implicit instance pointer");
                    for (size_t z = 0; z < a; z++)
                        if (text(c, u, m->as.function.params[a].name,
                                 mini_pname(c, u, m->as.function.params[z].name)))
                            mini_perror(c, u, m->name, "duplicate parameter");
                }
                type_syntax(c, owner, ref, m->as.function.return_type, api, true, 0);
                if (m->generic_count || d->generic_count) {
                    Names names = {.c = c, .owner = owner, .method = ref};
                    if (!(m->flags & MINI_STATIC)) {
                        char *self = "this";
                        mini_pappend(c, (void **)&names.names, &names.count, sizeof(self), &self);
                    }
                    for (size_t a = 0; a < m->as.function.param_count; a++)
                        add(&names, m->as.function.params[a].name);
                    statement_names(&names, m->as.function.body);
                }
            }
        }
    }
    return !c->result.message;
}

/* Heap residency is recursive for inline storage, never for borrowed pointers. */
static bool heapable(Context *c, Unit *unit, Token at, Type *type, size_t depth) {
    if (!type || c->result.message)
        return false;
    if (type->heap_state == 2)
        return true;
    if (depth > 128 || type->heap_state == 1) {
        mini_perror(c, unit, at, "recursive or excessive heap object layout");
        return false;
    }
    type->heap_state = 1;
    if (type->kind == TY_POINTER) {
        type->heap_state = 2;
        return true;
    }
    if (type->kind == TY_ARRAY) {
        bool result = heapable(c, unit, at, type->element, depth + 1);
        if (result)
            type->heap_state = 2;
        return result;
    }
    if (type->kind == TY_OPTIONAL || type->kind == TY_RESULT) {
        for (size_t i = 0; i < type->arg_count; i++)
            if (!heapable(c, unit, at, type->args[i], depth + 1))
                return false;
    }
    if (type->kind == TY_NOMINAL && type->ref.decl->kind == MINI_DECL_STRUCT) {
        if (!mini_pensure(c, type))
            return false;
        if (!type->ref.decl->as.struct_decl.is_class ||
            !mini_psubtype(c, type, c->allocatable_type)) {
            mini_perror(c, unit, at,
                        "stack-only value cannot reside on the heap; inherit AllocatableObject");
            return false;
        }
        for (size_t i = 0; i < type->field_count; i++)
            if (!heapable(c, unit, at, type->fields[i].type, depth + 1))
                return false;
    }
    if (!c->result.message)
        type->heap_state = 2;
    return !c->result.message;
}
bool mini_pheapable(Context *c, Unit *unit, Token at, Type *type) {
    return mini_pstorage(c, unit, at, type, false) && heapable(c, unit, at, type, 0);
}
