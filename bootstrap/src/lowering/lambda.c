#include "project_internal.h"
#include <string.h>

Local *mini_plocal(Body *b, const char *name) {
    for (size_t i = b->local_count; i > 0; i--)
        if (!strcmp(b->locals[i - 1]->name, name))
            return b->locals[i - 1];
    if (!b->outer || !b->lambda)
        return NULL;
    for (size_t i = 0; i < b->lambda->capture_count; i++)
        if (!strcmp(b->lambda->captures[i].local->name, name))
            return b->lambda->captures[i].local;
    Local *source = mini_plocal(b->outer, name);
    if (!source)
        return NULL;
    Context *c = b->c;
    if (!source->initialized) {
        mini_perror(c, b->env.unit, (Token){0}, "cannot capture an uninitialized binding");
        return NULL;
    }
    if (!source->immutable) {
        mini_perror(
            c, b->env.unit, (Token){0},
            "mutable binding capture is not supported; capture an explicit borrowed pointer");
        return NULL;
    }
    Type *t = source->type;
    bool scalar = t->kind >= TY_I32 && t->kind <= TY_BOOL;
    if (!scalar && t->kind != TY_POINTER &&
        !(t->kind == TY_NOMINAL && t->ref.decl->kind == MINI_DECL_ENUM)) {
        mini_perror(c, b->env.unit, (Token){0},
                    "capture requires a scalar val or borrowed pointer; aggregate/function "
                    "captures are not supported");
        return NULL;
    }
    Local *v = mini_palloc(c, sizeof(*v));
    *v = *source;
    v->code = mini_pformat(c, "closure->data.lambda_%zu.capture_%zu", b->lambda->id,
                           b->lambda->capture_count);
    v->depth = 0;
    Capture capture = {source, v};
    mini_pappend(c, (void **)&b->lambda->captures, &b->lambda->capture_count, sizeof(capture),
                 &capture);
    mini_pappend(c, (void **)&b->locals, &b->local_count, sizeof(v), &v);
    return v;
}
static bool explicit_return(const MiniStmt *s) {
    if (!s)
        return false;
    switch (s->kind) {
    case MINI_STMT_RETURN:
        return true;
    case MINI_STMT_BLOCK:
        for (size_t i = 0; i < s->as.block.count; i++)
            if (explicit_return(s->as.block.items[i]))
                return true;
        return false;
    case MINI_STMT_IF:
        return explicit_return(s->as.if_stmt.then_branch) ||
               explicit_return(s->as.if_stmt.else_branch);
    case MINI_STMT_WHILE:
        return explicit_return(s->as.while_stmt.body);
    default:
        return false;
    }
}
Value mini_plambda(Body *outer, const MiniExpr *e) {
    Context *c = outer->c;
    Unit *u = outer->env.unit;
    Token at = {.start = e->start};
    Type *expected = outer->expected;
    if (expected && expected->kind != TY_FUNCTION) {
        mini_perror(c, u, at, "lambda requires a function type context");
        return (Value){0};
    }
    if (c->lambda_count >= 4096) {
        mini_perror(c, u, at, "lambda instantiation limit exceeded");
        return (Value){0};
    }
    bool implicit = !e->as.lambda.explicit_params && expected && expected->arg_count == 1;
    size_t count = implicit ? 1 : e->as.lambda.param_count;
    if (expected && count != expected->arg_count) {
        mini_perror(c, u, at, "lambda parameter count differs from function type");
        return (Value){0};
    }
    if (!expected && explicit_return(e->as.lambda.body)) {
        mini_perror(c, u, at, "lambda with return requires a contextual function type");
        return (Value){0};
    }
    Lambda *lambda = mini_palloc(c, sizeof(*lambda));
    lambda->id = c->lambda_count;
    mini_pappend(c, (void **)&c->lambdas, &c->lambda_count, sizeof(lambda), &lambda);
    MiniDecl *decl = mini_palloc(c, sizeof(*decl));
    decl->kind = MINI_DECL_FUNCTION;
    decl->flags = MINI_STATIC;
    decl->name = at;
    decl->as.function.expression_body = true;
    Function function = {.ref = {u, decl},
                         .owner = outer->function->owner,
                         .result = expected ? expected->element : c->builtins[TY_VOID]};
    Body b = {.c = c,
              .function = &function,
              .env = outer->env,
              .out = &lambda->body,
              .outer = outer,
              .lambda = lambda,
              .depth = 1};
    Type **params = mini_palloc(c, count * sizeof(*params));
    for (size_t i = 0; i < count && !c->result.message; i++) {
        MiniLambdaParameter *p = implicit ? NULL : &e->as.lambda.params[i];
        char *name = implicit ? "it" : mini_pname(c, u, p->name);
        if (!strcmp(name, "this")) {
            mini_perror(c, u, at, "this is reserved for the current instance");
            return (Value){0};
        }
        for (size_t j = 0; j < b.local_count; j++)
            if (!strcmp(b.locals[j]->name, name))
                mini_perror(c, u, at, "duplicate lambda parameter");
        Type *type = p && p->type ? mini_presolve(c, b.env, p->type)
                     : expected   ? expected->args[i]
                                  : NULL;
        if (!type) {
            mini_perror(c, u, at,
                        "lambda parameter type needs an annotation or function type context");
            return (Value){0};
        }
        if (expected && type != expected->args[i]) {
            mini_perror(c, u, at, "lambda parameter type differs from function type");
            return (Value){0};
        }
        if (!mini_pstorage(c, u, at, type, false))
            return (Value){0};
        params[i] = type;
        Local *v = mini_palloc(c, sizeof(*v));
        *v = (Local){.name = name,
                     .code = mini_pformat(c, "param%zu", i),
                     .type = type,
                     .initialized = true,
                     .assigned = true,
                     .immutable = true};
        mini_pappend(c, (void **)&b.locals, &b.local_count, sizeof(v), &v);
    }
    const MiniStmt *block = e->as.lambda.body;
    bool returns = false;
    for (size_t i = 0; i < block->as.block.count && !c->result.message; i++) {
        const MiniStmt *s = block->as.block.items[i];
        bool tail = i + 1 == block->as.block.count && s->kind == MINI_STMT_EXPR;
        if (tail && !expected) {
            Value result = mini_pexpr(&b, s->as.expr);
            if (!result.type || result.polymorphic_view) {
                mini_perror(c, u, at, "invalid lambda return value");
                return (Value){0};
            }
            function.result = result.type;
            if (result.type->kind != TY_VOID)
                mini_pwrite(c, b.out, "return %s;\n", result.code);
            else
                mini_pwrite(c, b.out, "return;\n");
            returns = true;
        } else if (tail && function.result->kind != TY_VOID) {
            MiniStmt ret = {
                .kind = MINI_STMT_RETURN, .start = s->start, .as.ret.value = s->as.expr};
            returns |= mini_pstatement(&b, &ret);
        } else
            returns |= mini_pstatement(&b, s);
    }
    if (!returns && function.result->kind != TY_VOID)
        mini_perror(c, u, at, "not all lambda paths return a value");
    if (c->result.message)
        return (Value){0};
    lambda->type = mini_pfunctype(c, params, count, function.result);
    if (!lambda->type)
        return (Value){0};
    Buffer fields = {0};
    for (size_t i = 0; i < lambda->capture_count; i++)
        mini_pwrite(c, &fields, "%s.capture_%zu=%s", i ? "," : "", i,
                    lambda->captures[i].source->code);
    char *code =
        lambda->capture_count
            ? mini_pformat(c, "((%s){.tag=%zu,.data.lambda_%zu={%s}})",
                           mini_pctype(c, lambda->type), lambda->id, lambda->id, fields.data)
            : mini_pformat(c, "((%s){.tag=%zu})", mini_pctype(c, lambda->type), lambda->id);
    return (Value){.type = lambda->type, .code = code};
}
void mini_plambda_layout(Context *c, Buffer *out, Type *type) {
    mini_pwrite(c, out, "struct mini_type_%zu { uint32_t tag; union { uint8_t empty;\n", type->id);
    for (size_t i = 0; i < c->lambda_count; i++) {
        Lambda *l = c->lambdas[i];
        if (l->type != type || !l->capture_count)
            continue;
        mini_pwrite(c, out, "struct {\n");
        for (size_t j = 0; j < l->capture_count; j++)
            mini_pwrite(c, out, "%s capture_%zu;\n", mini_pctype(c, l->captures[j].local->type), j);
        mini_pwrite(c, out, "} lambda_%zu;\n", l->id);
    }
    mini_pwrite(c, out, "} data; };\n");
}
void mini_plambda_emit(Context *c, Buffer *out, bool prototypes) {
    for (size_t i = 0; i < c->lambda_count; i++) {
        Lambda *l = c->lambdas[i];
        Type *t = l->type;
        mini_pwrite(c, out, "static %s mini_lambda_%zu(%s* closure", mini_pctype(c, t->element),
                    l->id, mini_pctype(c, t));
        for (size_t j = 0; j < t->arg_count; j++)
            mini_pwrite(c, out, ", %s param%zu", mini_pctype(c, t->args[j]), j);
        if (prototypes)
            mini_pwrite(c, out, ");\n");
        else
            mini_pwrite(c, out, ") {\n%s}\n", l->body.data ? l->body.data : "");
    }
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        if (t->kind != TY_FUNCTION)
            continue;
        mini_pwrite(c, out, "static %s mini_invoke_%zu(%s closure", mini_pctype(c, t->element),
                    t->id, mini_pctype(c, t));
        for (size_t j = 0; j < t->arg_count; j++)
            mini_pwrite(c, out, ", %s param%zu", mini_pctype(c, t->args[j]), j);
        if (prototypes) {
            mini_pwrite(c, out, ");\n");
            continue;
        }
        mini_pwrite(c, out, ") { switch(closure.tag) {\n");
        for (size_t j = 0; j < c->lambda_count; j++) {
            Lambda *l = c->lambdas[j];
            if (l->type != t)
                continue;
            mini_pwrite(c, out, "case %zu: %smini_lambda_%zu(&closure", l->id,
                        t->element->kind == TY_VOID ? "" : "return ", l->id);
            for (size_t k = 0; k < t->arg_count; k++)
                mini_pwrite(c, out, ", param%zu", k);
            mini_pwrite(c, out, "); %s\n", t->element->kind == TY_VOID ? "return;" : "");
        }
        mini_pwrite(c, out, "default: abort(); } }\n");
    }
}
