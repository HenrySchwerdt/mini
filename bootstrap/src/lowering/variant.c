#include "project_internal.h"
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <limits.h>
#define C b->c
#define U b->env.unit
#define ERROR(t, ...) mini_perror(C, U, t, __VA_ARGS__)

static Value freeze(Body *b, Value v) {
    if (!v.type || v.type->kind == TY_VOID || C->result.message)
        return v;
    if (v.polymorphic_view) {
        ERROR(((Token){0}), "cannot copy a polymorphic object through a base pointer");
        return (Value){0};
    }
    char *tmp = mini_pformat(C, "tmp%zu", b->temporary++);
    mini_pwrite(C, b->out, "%s %s = %s;\n", mini_pctype(C, v.type), tmp, v.code);
    v.code = tmp;
    v.place = false;
    v.local = NULL;
    return v;
}
static Value convert(Body *b, Value v, Type *expected, Token at) {
    if (!v.type || !expected)
        return (Value){0};
    if (v.literal && expected->kind >= TY_I32 && expected->kind <= TY_ISIZE) {
        uint64_t max = expected->kind == TY_U8      ? UINT8_MAX
                       : expected->kind == TY_I32   ? INT32_MAX
                       : expected->kind == TY_ISIZE ? INTPTR_MAX
                                                    : SIZE_MAX;
        if (v.integer <= max)
            v.type = expected;
    }
    if (!mini_passignable(C, expected, v.type)) {
        ERROR(at, "variant payload or match arm has an incompatible type");
        return (Value){0};
    }
    if (expected != v.type)
        v.code = mini_pformat(C, "((%s)(%s))", mini_pctype(C, expected), v.code);
    v.type = expected;
    return v;
}
Value mini_pnative(Body *b, const MiniExpr *e, bool *recognized) {
    *recognized = false;
    const MiniExpr *callee = e->kind == MINI_EXPR_CALL ? e->as.call.callee : e;
    const MiniExpr *target = callee->kind == MINI_EXPR_MEMBER ? callee->as.member.target : NULL;
    Token name;
    if (callee->kind == MINI_EXPR_MEMBER)
        name = callee->as.member.name;
    else if (callee->kind == MINI_EXPR_IDENTIFIER)
        name = callee->as.identifier;
    else
        return (Value){0};
    char *variant = mini_pname(C, U, name);
    TypeKind kind;
    bool optional = !strcmp(variant, "Some") || !strcmp(variant, "None");
    bool result = !strcmp(variant, "Ok") || !strcmp(variant, "Err");
    if (!optional && !result)
        return (Value){0};
    if (target) {
        if (target->kind != MINI_EXPR_IDENTIFIER)
            return (Value){0};
        char *type_name = mini_pname(C, U, target->as.identifier);
        if (strcmp(type_name, "Optional") && strcmp(type_name, "Result"))
            return (Value){0};
        if ((optional && strcmp(type_name, "Optional")) ||
            (result && strcmp(type_name, "Result"))) {
            *recognized = true;
            ERROR(name, "variant does not belong to its native type");
            return (Value){0};
        }
    } else if (!b->expected && e->kind != MINI_EXPR_CALL)
        return (Value){0};
    *recognized = true;
    kind = optional ? TY_OPTIONAL : TY_RESULT;
    Type *type = NULL;
    if (target && target->type_arg_count) {
        MiniType ast = {.kind = MINI_TYPE_NAMED,
                        .as.name = target->as.identifier,
                        .args = target->type_args,
                        .arg_count = target->type_arg_count};
        type = mini_presolve(C, b->env, &ast);
    } else if (b->expected && b->expected->kind == kind)
        type = b->expected;
    bool none = !strcmp(variant, "None");
    bool error = !strcmp(variant, "Err");
    size_t count = e->kind == MINI_EXPR_CALL ? e->as.call.args.count : 0;
    if (count != (none ? 0u : 1u)) {
        ERROR(name, "incorrect native constructor payload count");
        return (Value){0};
    }
    Value payload = {0};
    if (!none) {
        Type *expected = type ? type->args[error ? 1 : 0] : NULL;
        payload = mini_pexpected(b, e->as.call.args.items[0], expected);
        if (!payload.type)
            return (Value){0};
        if (!type && kind == TY_OPTIONAL) {
            Type **args = mini_palloc(C, sizeof(Type *));
            args[0] = payload.type;
            type = mini_pvariant(C, kind, args, 1);
        }
        if (type)
            payload = convert(b, payload, type->args[error ? 1 : 0], name);
        payload = freeze(b, payload);
    }
    if (!type) {
        ERROR(name,
              "native constructor needs explicit type arguments or an Optional/Result context");
        return (Value){0};
    }
    if (C->result.message)
        return (Value){0};
    char *code = none ? mini_pformat(C, "((%s){.tag=0})", mini_pctype(C, type))
                      : mini_pformat(C, "((%s){.tag=%d,.data.%s=%s})", mini_pctype(C, type),
                                     kind == TY_OPTIONAL ? 1
                                     : error             ? 1
                                                         : 0,
                                     error ? "error" : "value", payload.code);
    return (Value){.type = type, .code = code, .readonly = payload.readonly};
}

typedef struct {
    bool any;
    char *binding;
    int tag;
    Type *payload;
    char *literal;
} Pattern;
static Pattern inspect(Body *b, Type *type, const MiniPattern *p) {
    Pattern out = {.tag = -1};
    if (!p) {
        ERROR(((Token){0}), "missing pattern payload");
        return out;
    }
    char *name = mini_pname(C, U, p->name);
    char *short_name = strrchr(name, '.');
    short_name = short_name ? short_name + 1 : name;
    if (p->name.kind == TOKEN_UNDERSCORE) {
        out.any = true;
        return out;
    }
    if (type->kind == TY_OPTIONAL || type->kind == TY_RESULT) {
        if (type->kind == TY_OPTIONAL && !strcmp(short_name, "Some")) {
            out.tag = 1;
            out.payload = type->args[0];
        } else if (type->kind == TY_OPTIONAL && !strcmp(short_name, "None"))
            out.tag = 0;
        else if (type->kind == TY_RESULT && !strcmp(short_name, "Ok")) {
            out.tag = 0;
            out.payload = type->args[0];
        } else if (type->kind == TY_RESULT && !strcmp(short_name, "Err")) {
            out.tag = 1;
            out.payload = type->args[1];
        }
        if (out.tag >= 0) {
            char *dot = strrchr(name, '.');
            if (dot) {
                *dot = 0;
                if (strcmp(name, type->kind == TY_OPTIONAL ? "Optional" : "Result"))
                    ERROR(p->name, "pattern has the wrong native type");
            }
            if ((out.payload != NULL) != (p->payload != NULL))
                ERROR(p->name, "pattern payload is missing or not permitted");
            return out;
        }
    }
    if (p->payload) {
        ERROR(p->name, "unknown variant or non-variant payload pattern");
        return out;
    }
    if (p->name.kind == TOKEN_TRUE || p->name.kind == TOKEN_FALSE) {
        if (type->kind != TY_BOOL)
            ERROR(p->name, "boolean pattern requires bool");
        out.tag = p->name.kind == TOKEN_TRUE ? 1 : 0;
        out.literal = out.tag ? "true" : "false";
        return out;
    }
    if (p->name.kind == TOKEN_INTEGER) {
        if (type->kind < TY_I32 || type->kind > TY_ISIZE)
            ERROR(p->name, "integer pattern requires integer subject");
        errno = 0;
        char *end;
        unsigned long long n = strtoull(name, &end, 10);
        if (errno || *end || n > INT32_MAX)
            ERROR(p->name, "integer pattern exceeds supported range");
        out.literal = mini_pformat(C, "%llu", n);
        return out;
    }
    if (type->kind == TY_NOMINAL && type->ref.decl->kind == MINI_DECL_ENUM) {
        char *dot = strrchr(name, '.');
        if (dot) {
            *dot = 0;
            Ref ref = mini_plookup(C, U, name, p->name);
            if (ref.decl != type->ref.decl)
                ERROR(p->name, "enum pattern has the wrong type");
        }
        for (size_t i = 0; i < type->ref.decl->as.enum_decl.variant_count; i++)
            if (!strcmp(short_name,
                        mini_pname(C, type->ref.unit, type->ref.decl->as.enum_decl.variants[i]))) {
                out.tag = (int)i;
                out.literal = mini_pformat(C, "%zu", i);
                return out;
            }
        if (dot) {
            ERROR(p->name, "unknown enum variant");
            return out;
        }
    }
    if (p->name.kind == TOKEN_IDENTIFIER && !strchr(name, '.') && strcmp(name, "Some") &&
        strcmp(name, "None") && strcmp(name, "Ok") && strcmp(name, "Err")) {
        out.any = true;
        out.binding = name;
        return out;
    }
    ERROR(p->name, "pattern is incompatible with its subject type");
    return out;
}
static bool exhaustive(Body *b, Type *t, const MiniPattern **patterns, size_t count, size_t depth);
static bool covered(Body *b, Type *t, const MiniPattern **previous, size_t count,
                    const MiniPattern *next, size_t depth) {
    if (!next) {
        ERROR(((Token){0}), "missing pattern");
        return false;
    }
    if (depth > 128) {
        ERROR(next->name, "pattern nesting limit exceeded");
        return false;
    }
    Pattern n = inspect(b, t, next);
    if (n.any)
        return exhaustive(b, t, previous, count, depth + 1);
    const MiniPattern **children = mini_palloc(C, count * sizeof(*children));
    size_t child_count = 0;
    for (size_t i = 0; i < count; i++) {
        Pattern p = inspect(b, t, previous[i]);
        if (p.any)
            return true;
        bool same =
            n.tag >= 0 ? p.tag == n.tag : (p.literal && n.literal && !strcmp(p.literal, n.literal));
        if (!same)
            continue;
        if (!n.payload)
            return true;
        children[child_count++] = previous[i]->payload;
    }
    return n.payload && covered(b, n.payload, children, child_count, next->payload, depth + 1);
}
static bool exhaustive(Body *b, Type *t, const MiniPattern **patterns, size_t count, size_t depth) {
    if (depth > 128)
        return false;
    for (size_t i = 0; i < count; i++)
        if (inspect(b, t, patterns[i]).any)
            return true;
    size_t variants = t->kind == TY_OPTIONAL || t->kind == TY_RESULT || t->kind == TY_BOOL ? 2
                      : t->kind == TY_NOMINAL && t->ref.decl->kind == MINI_DECL_ENUM
                          ? t->ref.decl->as.enum_decl.variant_count
                          : 0;
    if (!variants)
        return false;
    for (size_t variant = 0; variant < variants; variant++) {
        const MiniPattern **children = mini_palloc(C, count * sizeof(*children));
        size_t n = 0;
        Type *payload = NULL;
        bool found = false;
        for (size_t i = 0; i < count; i++) {
            Pattern p = inspect(b, t, patterns[i]);
            if (p.tag != (int)variant)
                continue;
            found = true;
            payload = p.payload;
            if (payload && patterns[i])
                children[n++] = patterns[i]->payload;
        }
        if (!found || (payload && !exhaustive(b, payload, children, n, depth + 1)))
            return false;
    }
    return true;
}
static char *condition(Body *b, Type *type, const MiniPattern *p, const char *value) {
    if (!p) {
        ERROR(((Token){0}), "missing pattern");
        return "false";
    }
    Pattern info = inspect(b, type, p);
    if (info.any)
        return "true";
    if (type->kind == TY_OPTIONAL || type->kind == TY_RESULT) {
        char *test = mini_pformat(C, "(%s).tag == %d", value, info.tag);
        if (info.payload) {
            char *payload =
                mini_pformat(C, "(%s).data.%s", value,
                             type->kind == TY_RESULT && info.tag == 1 ? "error" : "value");
            return mini_pformat(C, "((%s) && (%s))", test,
                                condition(b, info.payload, p->payload, payload));
        }
        return test;
    }
    return mini_pformat(C, "(%s) == (%s)", value, info.literal ? info.literal : "0");
}
static void bind(Body *b, Type *type, const MiniPattern *p, const char *value, bool readonly) {
    Pattern info = inspect(b, type, p);
    if (info.binding) {
        if (!strcmp(info.binding, "this")) {
            ERROR(p->name, "this cannot be bound in a pattern");
            return;
        }
        Local *v = mini_palloc(C, sizeof(*v));
        v->name = info.binding;
        v->type = type;
        v->initialized = true;
        v->assigned = true;
        v->loop_depth = b->loop_depth;
        v->readonly = readonly;
        v->depth = b->depth;
        v->code = mini_pformat(C, "binding%zu", b->temporary++);
        mini_pappend(C, (void **)&b->locals, &b->local_count, sizeof(v), &v);
        mini_pwrite(C, b->out, "%s %s = %s;\n", mini_pctype(C, type), v->code, value);
    }
    if (info.payload && p && p->payload)
        bind(b, info.payload, p->payload,
             mini_pformat(C, "(%s).data.%s", value,
                          type->kind == TY_RESULT && info.tag == 1 ? "error" : "value"),
             readonly);
}
Value mini_pmatch(Body *b, const MiniExpr *e) {
    Type *expected = b->expected;
    b->expected = NULL;
    Value subject = freeze(b, mini_pexpr(b, e->as.match.subject));
    b->expected = expected;
    if (!subject.type)
        return (Value){0};
    size_t locals = b->local_count;
    LocalState *before = mini_psnapshot(b, locals);
    LocalState *after = mini_palloc(C, locals * sizeof(*after));
    for (size_t i = 0; i < locals; i++)
        after[i].initialized = true;
    Type *result = NULL;
    char *tmp = mini_pformat(C, "tmp%zu", b->temporary++);
    Buffer arms = {0};
    Buffer *outer = b->out;
    b->out = &arms;
    const MiniPattern **previous = mini_palloc(C, e->as.match.arm_count * sizeof(*previous));
    size_t previous_count = 0;
    for (size_t i = 0; i < e->as.match.arm_count && !C->result.message; i++) {
        MiniMatchArm *arm = &e->as.match.arms[i];
        MiniPattern fallback = {.name = arm->pattern};
        const MiniPattern *p = arm->tree ? arm->tree : &fallback;
        char *test = condition(b, subject.type, p, subject.code);
        if (covered(b, subject.type, previous, previous_count, p, 0)) {
            ERROR(p->name, "duplicate or unreachable match arm");
            break;
        }
        mini_pwrite(C, &arms, "%sif (%s) {\n", i ? "else " : "", test);
        mini_prestore(b, before, locals);
        b->depth++;
        bind(b, subject.type, p, subject.code, subject.readonly);
        Value v = mini_pexpected(b, arm->value, expected ? expected : result);
        if (!v.type)
            break;
        if (!result)
            result = v.type;
        else
            v = convert(b, v, result, p->name);
        if (!v.type)
            break;
        if (result->kind != TY_VOID)
            mini_pwrite(C, &arms, "%s = %s;\n", tmp, v.code);
        mini_pwrite(C, &arms, "}\n");
        b->depth--;
        b->local_count = locals;
        for (size_t j = 0; j < locals; j++) {
            after[j].initialized &= b->locals[j]->initialized;
            after[j].assigned |= b->locals[j]->assigned;
            after[j].readonly |= b->locals[j]->readonly;
        }
        previous[previous_count++] = p;
    }
    b->out = outer;
    b->local_count = locals;
    mini_prestore(b, after, locals);
    if (!result || !exhaustive(b, subject.type, previous, previous_count, 0))
        ERROR((Token){.start = e->start}, "match must be nonempty and exhaustive");
    if (C->result.message || !result)
        return (Value){0};
    if (result->kind != TY_VOID)
        mini_pwrite(C, outer, "%s %s;\n", mini_pctype(C, result), tmp);
    mini_pwrite(C, outer, "%s", arms.data ? arms.data : "");
    return (Value){.type = result, .code = result->kind == TY_VOID ? "0" : tmp};
}
