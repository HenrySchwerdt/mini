#include "project_internal.h"
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <errno.h>

#define C b->c
#define U b->env.unit
#define ERROR(e, ...) mini_perror(C, U, (Token){.start = (e)->start}, __VA_ARGS__)
static Value invalid(void) {
    return (Value){0};
}
static bool integer(Type *t) {
    return t && t->kind >= TY_I32 && t->kind <= TY_ISIZE;
}
static Value freeze(Body *b, Value v) {
    if (!v.type || v.type->kind == TY_VOID || C->result.message)
        return v;
    if (v.polymorphic_view) {
        mini_perror(C, U, (Token){0}, "cannot copy a polymorphic object through a base pointer");
        return invalid();
    }
    char *name = mini_pformat(C, "tmp%zu", b->temporary++);
    mini_pwrite(C, b->out, "%s %s = %s;\n", mini_pctype(C, v.type), name, v.code);
    v.code = name;
    v.place = false;
    v.local = NULL;
    return v;
}
static Value coerce(Body *b, Value v, Type *expected, const MiniExpr *at) {
    if (!v.type || !expected)
        return invalid();
    if (v.polymorphic_view) {
        ERROR(at, "cannot copy a polymorphic object through a base pointer");
        return invalid();
    }
    if (v.literal && integer(expected)) {
        uint64_t max = expected->kind == TY_U8      ? UINT8_MAX
                       : expected->kind == TY_I32   ? INT32_MAX
                       : expected->kind == TY_ISIZE ? INTPTR_MAX
                                                    : SIZE_MAX;
        if (v.integer <= max) {
            v.type = expected;
            return v;
        }
    }
    if (!mini_passignable(C, expected, v.type)) {
        ERROR(at, "type mismatch: expected %s, got %s", mini_pctype(C, expected),
              mini_pctype(C, v.type));
        return invalid();
    }
    if (v.type != expected)
        v.code = mini_pformat(C, "((%s)(%s))", mini_pctype(C, expected), v.code);
    v.type = expected;
    return v;
}
static bool access(Body *b, unsigned flags, Type *owner, const MiniExpr *at) {
    if ((flags & MINI_PRIVATE) && b->function->owner != owner) {
        ERROR(at, "private member is not accessible");
        return false;
    }
    if ((flags & MINI_PROTECTED) &&
        (!b->function->owner || !mini_psubtype(C, b->function->owner, owner))) {
        ERROR(at, "protected member is not accessible");
        return false;
    }
    if (flags & MINI_INTERNAL) {
        const char *from = U->source.module ? U->source.module : "main";
        const char *to = owner->ref.unit->source.module ? owner->ref.unit->source.module : "main";
        if (strcmp(from, to)) {
            ERROR(at, "internal member is not accessible");
            return false;
        }
    }
    return true;
}
static char *path_name(Body *b, const MiniExpr *e) {
    if (e->kind == MINI_EXPR_IDENTIFIER)
        return mini_pname(C, U, e->as.identifier);
    if (e->kind != MINI_EXPR_MEMBER)
        return NULL;
    char *prefix = path_name(b, e->as.member.target);
    return prefix ? mini_pformat(C, "%s.%s", prefix, mini_pname(C, U, e->as.member.name)) : NULL;
}
static Type **arguments(Body *b, MiniType **asts, size_t count) {
    Type **args = mini_palloc(C, count * sizeof(Type *));
    for (size_t i = 0; i < count; i++)
        args[i] = mini_presolve(C, b->env, asts[i]);
    return args;
}
static bool storage(Body *b, Type *t, const MiniExpr *at) {
    if (!t)
        return false;
    if (!mini_pstorage(C, U, (Token){.start = at->start}, t, false))
        return false;
    if (t->kind == TY_VOID) {
        ERROR(at, "a value type is required");
        return false;
    }
    if (t->kind == TY_NOMINAL && t->ref.decl->kind == MINI_DECL_STRUCT) {
        if (!mini_pensure(C, t))
            return false;
        if (t->ref.decl->as.struct_decl.is_interface || (t->ref.decl->flags & MINI_ABSTRACT)) {
            ERROR(at, "cannot instantiate an interface or abstract class");
            return false;
        }
    }
    return true;
}
static Value call_builtin(Body *b, const MiniExpr *e, const char *name, bool *recognized) {
    Type *params[3] = {0};
    size_t count = 0;
    Type *result = NULL;
    char *callee = NULL;
    Type *byteptr = mini_ppointer(C, C->builtins[TY_U8]);
    bool string_ok = false;
    if (!strcmp(name, "heap.alloc")) {
        params[0] = C->builtins[TY_USIZE];
        count = 1;
        Type **payload = mini_palloc(C, sizeof(*payload));
        payload[0] = byteptr;
        result = mini_pvariant(C, TY_OPTIONAL, payload, 1);
        callee = "mini_rt_alloc";
    } else if (!strcmp(name, "heap.realloc")) {
        params[0] = byteptr;
        params[1] = C->builtins[TY_USIZE];
        count = 2;
        Type **payload = mini_palloc(C, sizeof(*payload));
        payload[0] = byteptr;
        result = mini_pvariant(C, TY_OPTIONAL, payload, 1);
        callee = "mini_rt_realloc";
    } else if (!strcmp(name, "heap.free")) {
        params[0] = byteptr;
        count = 1;
        result = C->builtins[TY_VOID];
        callee = "mini_rt_free";
    } else if (!strcmp(name, "print_i32")) {
        params[0] = C->builtins[TY_I32];
        count = 1;
        result = C->builtins[TY_VOID];
        callee = "mini_rt_print_i32";
    } else if (!strcmp(name, "read") || !strcmp(name, "write")) {
        params[0] = C->builtins[TY_I32];
        params[1] = byteptr;
        params[2] = C->builtins[TY_USIZE];
        count = 3;
        result = C->builtins[TY_ISIZE];
        callee = !strcmp(name, "read") ? "mini_rt_read" : "mini_rt_write";
        string_ok = !strcmp(name, "write");
    } else if (!strcmp(name, "runtime.threadStart")) {
        params[0] = mini_pfunctype(C, NULL, 0, C->builtins[TY_VOID]);
        params[1] = mini_ppointer(C, C->builtins[TY_I32]);
        count = 2;
        Type **payload = mini_palloc(C, sizeof(*payload));
        payload[0] = byteptr;
        result = mini_pvariant(C, TY_OPTIONAL, payload, 1);
        callee = "mini_uv_start";
        C->uv_task_type = params[0];
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.mutexCreate")) {
        params[0] = mini_ppointer(C, C->builtins[TY_I32]);
        count = 1;
        Type **payload = mini_palloc(C, sizeof(*payload));
        payload[0] = byteptr;
        result = mini_pvariant(C, TY_OPTIONAL, payload, 1);
        callee = "mini_uv_mutex_create";
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.threadJoin") || !strcmp(name, "runtime.threadIsAlive") ||
               !strcmp(name, "runtime.mutexLock") || !strcmp(name, "runtime.mutexUnlock") ||
               !strcmp(name, "runtime.mutexTryLock") || !strcmp(name, "runtime.mutexClose")) {
        params[0] = byteptr;
        count = 1;
        result =
            !strcmp(name, "runtime.threadIsAlive") ? C->builtins[TY_BOOL] : C->builtins[TY_I32];
        callee = !strcmp(name, "runtime.threadJoin")      ? "mini_uv_join"
                 : !strcmp(name, "runtime.threadIsAlive") ? "mini_uv_alive"
                 : !strcmp(name, "runtime.mutexLock")     ? "mini_uv_mutex_lock"
                 : !strcmp(name, "runtime.mutexUnlock")   ? "mini_uv_mutex_unlock"
                 : !strcmp(name, "runtime.mutexTryLock")  ? "mini_uv_mutex_trylock"
                                                          : "mini_uv_mutex_close";
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.threadSleep")) {
        params[0] = C->builtins[TY_USIZE];
        count = 1;
        result = C->builtins[TY_VOID];
        callee = "mini_uv_sleep";
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.uvOutOfMemory") || !strcmp(name, "runtime.uvBusy")) {
        result = C->builtins[TY_I32];
        callee = !strcmp(name, "runtime.uvOutOfMemory") ? "mini_uv_oom" : "mini_uv_busy";
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.uvRefused") || !strcmp(name, "runtime.uvReset")) {
        result = C->builtins[TY_I32];
        callee = !strcmp(name, "runtime.uvRefused") ? "mini_uv_refused" : "mini_uv_reset";
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.tcpConnect") || !strcmp(name, "runtime.tcpListen") ||
               !strcmp(name, "runtime.tcpAccept")) {
        bool connect = !strcmp(name, "runtime.tcpConnect");
        bool listen = !strcmp(name, "runtime.tcpListen");
        size_t at = 0;
        if (connect)
            params[at++] = byteptr;
        if (connect || listen)
            params[at++] = C->builtins[TY_I32];
        else
            params[at++] = byteptr;
        params[at++] = mini_ppointer(C, C->builtins[TY_I32]);
        count = at;
        string_ok = connect;
        Type **payload = mini_palloc(C, sizeof(*payload));
        payload[0] = byteptr;
        result = mini_pvariant(C, TY_OPTIONAL, payload, 1);
        callee = connect ? "mini_uv_tcp_connect" : listen ? "mini_uv_tcp_listen" : "mini_uv_tcp_accept";
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.tcpWrite") || !strcmp(name, "runtime.tcpRead")) {
        params[0] = byteptr;
        params[1] = byteptr;
        params[2] = C->builtins[TY_USIZE];
        count = 3;
        result = C->builtins[TY_ISIZE];
        callee = !strcmp(name, "runtime.tcpWrite") ? "mini_uv_tcp_write" : "mini_uv_tcp_read";
        string_ok = !strcmp(name, "runtime.tcpWrite");
        C->result.requires_libuv = true;
    } else if (!strcmp(name, "runtime.tcpClose") || !strcmp(name, "runtime.tcpListenerClose")) {
        params[0] = byteptr;
        count = 1;
        result = C->builtins[TY_I32];
        callee = !strcmp(name, "runtime.tcpClose") ? "mini_uv_tcp_close" : "mini_uv_listener_close";
        C->result.requires_libuv = true;
    } else {
        *recognized = false;
        return invalid();
    }
    *recognized = true;
    if (e->as.call.args.count != count) {
        ERROR(e, "incorrect runtime argument count");
        return invalid();
    }
    if (e->as.call.callee->type_arg_count) {
        ERROR(e, "runtime functions are not generic");
        return invalid();
    }
    Buffer args = {0};
    char *task = NULL;
    char *status = NULL;
    for (size_t i = 0; i < count && !C->result.message; i++) {
        Value v = coerce(b, mini_pexpected(b, e->as.call.args.items[i], params[i]), params[i],
                         e->as.call.args.items[i]);
        if (v.readonly && !string_ok) {
            ERROR(e, "immutable string cannot be passed to a mutable pointer parameter");
            return invalid();
        }
        v = freeze(b, v);
        if (!v.type)
            return invalid();
        if (!strcmp(name, "runtime.threadStart")) {
            if (i == 0)
                task = v.code;
            else
                status = v.code;
        }
        mini_pwrite(C, &args, "%s%s", i ? ", " : "", v.code);
    }
    char *code = !strcmp(name, "runtime.threadStart")
                     ? mini_pformat(C, "mini_uv_start(&%s, sizeof(%s), mini_uv_run_task, %s)", task,
                                    task, status)
                     : mini_pformat(C, "%s(%s)", callee, args.data ? args.data : "");
    if (result->kind == TY_OPTIONAL) {
        char *raw = mini_pformat(C, "tmp%zu", b->temporary++);
        char *wrapped = mini_pformat(C, "tmp%zu", b->temporary++);
        mini_pwrite(C, b->out,
                    "uint8_t* %s = %s;\n%s %s = {.tag=0};\n"
                    "if (%s) { %s.tag=1; %s.data.value=%s; }\n",
                    raw, code, mini_pctype(C, result), wrapped, raw, wrapped, wrapped, raw);
        return (Value){.type = result, .code = wrapped};
    }
    if (result->kind == TY_VOID) {
        mini_pwrite(C, b->out, "%s;\n", code);
        return (Value){.type = result, .code = "0"};
    }
    return freeze(b, (Value){.type = result, .code = code});
}
Value mini_pinvoke(Body *b, const MiniExpr *e, Value callee) {
    callee = freeze(b, callee);
    Type *type = callee.type;
    if (!type || type->kind != TY_FUNCTION) {
        ERROR(e, "value is not callable");
        return invalid();
    }
    if (e->as.call.callee->type_arg_count || e->as.call.args.count != type->arg_count) {
        ERROR(e, "incorrect lambda argument count or generic arguments");
        return invalid();
    }
    Buffer args = {0};
    mini_pwrite(C, &args, "%s", callee.code);
    for (size_t i = 0; i < type->arg_count && !C->result.message; i++) {
        const MiniExpr *arg = e->as.call.args.items[i];
        Value v = freeze(b, coerce(b, mini_pexpected(b, arg, type->args[i]), type->args[i], arg));
        if (!v.type)
            return invalid();
        if (v.readonly && type->args[i]->kind == TY_POINTER) {
            ERROR(arg, "immutable string cannot escape into a mutable pointer parameter");
            return invalid();
        }
        mini_pwrite(C, &args, ", %s", v.code);
    }
    if (C->result.message)
        return invalid();
    char *code = mini_pformat(C, "mini_invoke_%zu(%s)", type->id, args.data);
    if (type->element->kind == TY_VOID) {
        mini_pwrite(C, b->out, "%s;\n", code);
        return (Value){.type = type->element, .code = "0"};
    }
    return freeze(b, (Value){.type = type->element, .code = code});
}
static Value call(Body *b, const MiniExpr *e) {
    const MiniExpr *callee = e->as.call.callee;
    if ((callee->kind == MINI_EXPR_IDENTIFIER &&
         mini_plocal(b, mini_pname(C, U, callee->as.identifier))) ||
        (callee->kind != MINI_EXPR_IDENTIFIER && callee->kind != MINI_EXPR_MEMBER))
        return mini_pinvoke(b, e, mini_pexpected(b, callee, NULL));
    char *name = path_name(b, callee);
    bool declared = false;
    if (name && !strchr(name, '.')) {
        for (size_t i = 0; i < C->unit_count; i++)
            if (!strcmp(C->units[i].package, U->package))
                for (size_t j = 0; j < C->units[i].ast->count; j++)
                    if (!strcmp(name, mini_pname(C, &C->units[i], C->units[i].ast->items[j]->name)))
                        declared = true;
        for (size_t i = 0; i < U->ast->import_count; i++)
            if (!U->ast->imports[i].star) {
                MiniImport *im = &U->ast->imports[i];
                char *p = mini_pname(C, U, im->path);
                char *alias = im->alias.length ? mini_pname(C, U, im->alias)
                                               : (strrchr(p, '.') ? strrchr(p, '.') + 1 : p);
                if (!strcmp(name, alias))
                    declared = true;
            }
    }
    if (name && b->function->owner && mini_pmethod(b->function->owner, name))
        declared = true;
    if (name && !declared) {
        bool recognized = false;
        Value v = call_builtin(b, e, name, &recognized);
        if (recognized)
            return v;
    }
    Ref ref = {0};
    Type *owner = NULL;
    Method *method = NULL;
    Value receiver = {0};
    bool direct = false;
    const MiniExpr *target = callee->kind == MINI_EXPR_MEMBER ? callee->as.member.target : NULL;
    bool is_super = target && target->kind == MINI_EXPR_IDENTIFIER &&
                    !strcmp(mini_pname(C, U, target->as.identifier), "super");
    if (is_super) {
        if (!b->function->owner) {
            ERROR(e, "super is only valid in a method");
            return invalid();
        }
        owner = b->function->owner->base;
        if (target->type_arg_count) {
            if (target->type_arg_count != 1) {
                ERROR(e, "super requires exactly one supertype");
                return invalid();
            }
            owner = mini_presolve(C, b->env, target->type_args[0]);
            bool found = false;
            for (size_t i = 0; i < b->function->owner->super_count; i++)
                if (b->function->owner->supers[i] == owner)
                    found = true;
            if (!found) {
                ERROR(e, "qualified super must name a direct supertype");
                return invalid();
            }
        }
        if (!owner) {
            ERROR(e, "class has no superclass");
            return invalid();
        }
        Local *self = mini_plocal(b, "this");
        if (!self) {
            ERROR(e, "super requires an instance receiver");
            return invalid();
        }
        receiver = (Value){.type = mini_ppointer(C, owner),
                           .code = mini_pformat(
                               C, "((%s)%s)", mini_pctype(C, mini_ppointer(C, owner)), self->code)};
        direct = true;
    } else if (target) {
        /* A local/member receiver takes priority over a package-qualified function. */
        const MiniExpr *root = target;
        while (root->kind == MINI_EXPR_MEMBER)
            root = root->as.member.target;
        bool expression_receiver = root->kind != MINI_EXPR_IDENTIFIER ||
                                   mini_plocal(b, mini_pname(C, U, root->as.identifier));
        if (expression_receiver) {
            receiver = mini_pexpr(b, target);
            if (receiver.type && receiver.type->kind == TY_NOMINAL &&
                receiver.type->ref.decl->kind == MINI_DECL_STRUCT &&
                receiver.type->ref.decl->as.struct_decl.is_class) {
                /* Borrow stable storage, never a copied instance. Temporaries live until
                 * the enclosing expression/block ends in the generated C. */
                if (!receiver.place)
                    receiver = freeze(b, receiver);
                receiver.code = mini_pformat(C, "(&(%s))", receiver.code);
                receiver.type = mini_ppointer(C, receiver.type);
                receiver.place = false;
            }
            receiver = freeze(b, receiver);
            if (!receiver.type || receiver.type->kind != TY_POINTER ||
                receiver.type->element->kind != TY_NOMINAL) {
                ERROR(e, "method receiver must be a pointer to a class or struct");
                return invalid();
            }
            owner = receiver.type->element;
        }
    }
    bool static_receiver = false;
    if (target && !owner) {
        char *type_name = path_name(b, target);
        if (type_name) {
            Token token =
                target->kind == MINI_EXPR_IDENTIFIER
                    ? target->as.identifier
                    : (Token){TOKEN_IDENTIFIER, target->start, target->end - target->start};
            MiniType ast = {.kind = MINI_TYPE_NAMED,
                            .as.name = token,
                            .args = target->type_args,
                            .arg_count = target->type_arg_count};
            owner = mini_presolve(C, b->env, &ast);
            static_receiver = true;
        }
    }
    if (!target && b->function->owner) {
        owner = b->function->owner;
        if (!mini_pensure(C, owner))
            return invalid();
        method = mini_pmethod(owner, name);
        if (!method) {
            ERROR(e, "unknown method '%s'", name);
            return invalid();
        }
        if (!(method->flags & MINI_STATIC)) {
            Local *self = mini_plocal(b, "this");
            if (!self) {
                ERROR(e, "instance method requires this");
                return invalid();
            }
            receiver = (Value){.type = self->type, .code = self->code};
        }
    }
    if (owner) {
        if (!mini_pensure(C, owner))
            return invalid();
        if (static_receiver) {
            bool recognized = false;
            Value allocated = mini_pobject_call(
                b, e, owner, mini_pname(C, U, callee->as.member.name), &recognized);
            if (recognized)
                return allocated;
        }
        method = mini_pmethod(owner, target ? mini_pname(C, U, callee->as.member.name) : name);
        if (!method && !static_receiver && target) {
            char *field_name = mini_pname(C, U, callee->as.member.name);
            for (size_t i = 0; i < owner->field_count; i++) {
                Field *field = &owner->fields[i];
                if (!strcmp(field_name, field->name) && field->type->kind == TY_FUNCTION) {
                    if (!access(b, field->flags, field->owner, e))
                        return invalid();
                    return mini_pinvoke(
                        b, e,
                        (Value){.type = field->type,
                                .code = mini_pformat(C, "(%s)->field_%zu", receiver.code, i)});
                }
            }
        }
        if (!method || method->conflict) {
            ERROR(e, "unknown or ambiguous method");
            return invalid();
        }
        if (!access(b, method->flags, method->owner, e))
            return invalid();
        if (direct && method->abstract) {
            ERROR(e, "cannot call an abstract super method");
            return invalid();
        }
        if (static_receiver && !(method->flags & MINI_STATIC)) {
            ERROR(e, "instance method requires an instance receiver");
            return invalid();
        }
        if (!static_receiver && target && (method->flags & MINI_STATIC)) {
            ERROR(e, "static method must be called through its class");
            return invalid();
        }
        ref = method->ref;
    } else {
        if (!name) {
            ERROR(e, "only named functions and methods can be called");
            return invalid();
        }
        if (callee->kind == MINI_EXPR_IDENTIFIER && mini_plocal(b, name)) {
            ERROR(e, "local value is not callable");
            return invalid();
        }
        ref = mini_plookup(C, U, name,
                           callee->kind == MINI_EXPR_IDENTIFIER ? callee->as.identifier
                                                                : callee->as.member.name);
        if (!ref.decl)
            return invalid();
        if (ref.decl->kind != MINI_DECL_FUNCTION) {
            ERROR(e, "declaration is not a function");
            return invalid();
        }
    }
    mini_pdeprecated(C, U, (Token){.start = e->start}, ref);
    Type **typeargs = arguments(b, callee->type_args, callee->type_arg_count);
    Function *fn = NULL;
    if (!method || direct || !method->open)
        fn =
            mini_pfunction(C, ref, method ? method->owner : NULL, typeargs, callee->type_arg_count);
    else if (callee->type_arg_count) {
        ERROR(e, "generic method arguments are not supported");
        return invalid();
    }
    if (C->result.message || ((!method || direct || !method->open) && !fn))
        return invalid();
    size_t n = fn ? fn->param_count : method->param_count;
    size_t first = method && !(method->flags & MINI_STATIC) ? 1 : 0;
    if (e->as.call.args.count + first != n) {
        ERROR(e, "incorrect argument count");
        return invalid();
    }
    Buffer args = {0};
    if (first)
        mini_pwrite(
            C, &args, "((%s)%s)",
            mini_pctype(C, mini_ppointer(C, (direct || !method->open) ? method->owner : owner)),
            receiver.code);
    for (size_t i = first; i < n && !C->result.message; i++) {
        const MiniExpr *arg = e->as.call.args.items[i - first];
        Type *expected = fn ? fn->params[i] : method->params[i];
        Value v = coerce(b, mini_pexpected(b, arg, expected), expected, arg);
        if (v.readonly && expected && expected->kind == TY_POINTER) {
            ERROR(arg, "immutable string cannot escape into a mutable pointer parameter");
            return invalid();
        }
        v = freeze(b, v);
        if (!v.type)
            return invalid();
        mini_pwrite(C, &args, "%s%s", i ? ", " : "", v.code);
    }
    if (C->result.message)
        return invalid();
    char *symbol;
    if (method && method->open && !direct)
        symbol =
            mini_pformat(C, "mini_dispatch_%zu_%zu", owner->id, (size_t)(method - owner->methods));
    else if (ref.decl->as.function.is_extern)
        symbol = mini_pextern_name(C, ref);
    else
        symbol = mini_pformat(C, "mini_fn_%zu", fn->id);
    Type *result = fn ? fn->result : method->result;
    char *code = mini_pformat(C, "%s(%s)", symbol, args.data ? args.data : "");
    if (ref.decl->as.function.is_extern && result->kind == TY_POINTER)
        code = mini_pformat(C, "((%s)mini_rt_checked_pointer(%s))", mini_pctype(C, result), code);
    if (result->kind == TY_VOID) {
        mini_pwrite(C, b->out, "%s;\n", code);
        return (Value){.type = result, .code = "0"};
    }
    return freeze(b, (Value){.type = result, .code = code});
}
static Value member(Body *b, const MiniExpr *e) {
    const MiniExpr *target = e->as.member.target;
    char *name = mini_pname(C, U, e->as.member.name);
    if (target->kind == MINI_EXPR_IDENTIFIER &&
        !mini_plocal(b, mini_pname(C, U, target->as.identifier))) {
        Ref ref =
            mini_plookup(C, U, mini_pname(C, U, target->as.identifier), target->as.identifier);
        if (ref.decl && ref.decl->kind == MINI_DECL_ENUM) {
            for (size_t i = 0; i < ref.decl->as.enum_decl.variant_count; i++)
                if (!strcmp(name, mini_pname(C, ref.unit, ref.decl->as.enum_decl.variants[i])))
                    return (Value){.type = mini_pnominal(C, ref, NULL, 0),
                                   .code = mini_pformat(C, "%zu", i)};
            ERROR(e, "unknown enum variant");
            return invalid();
        }
        if (C->result.message)
            return invalid();
    }
    Value v = mini_pexpr(b, target);
    if (!v.type)
        return invalid();
    Type *t = v.type;
    bool ptr = t->kind == TY_POINTER;
    if (ptr)
        t = t->element;
    if (t->kind != TY_NOMINAL || t->ref.decl->kind != MINI_DECL_STRUCT || !mini_pensure(C, t)) {
        ERROR(e, "field access requires a record");
        return invalid();
    }
    for (size_t i = 0; i < t->field_count; i++)
        if (!strcmp(t->fields[i].name, name)) {
            if (!access(b, t->fields[i].flags, t->fields[i].owner, e))
                return invalid();
            return (Value){.type = t->fields[i].type,
                           .code = mini_pformat(C, "(%s)%sfield_%zu", v.code, ptr ? "->" : ".", i),
                           .place = ptr || v.place,
                           .readonly = v.readonly,
                           .immutable_place = (t->fields[i].flags & MINI_IMMUTABLE) != 0};
        }
    ERROR(e, "unknown field '%s'", name);
    return invalid();
}
Value mini_pexpected(Body *b, const MiniExpr *e, Type *expected) {
    Type *old = b->expected;
    b->expected = expected;
    Value result = mini_pexpr(b, e);
    b->expected = old;
    return result;
}
Value mini_pexpr(Body *b, const MiniExpr *e) {
    if (!e || C->result.message)
        return invalid();
    bool native = false;
    Value constructor = mini_pnative(b, e, &native);
    if (native)
        return constructor;
    switch (e->kind) {
    case MINI_EXPR_INTEGER: {
        char *text = mini_pname(C, U, e->as.literal);
        errno = 0;
        char *end;
        unsigned long long n = strtoull(text, &end, 10);
        if (errno || *end || n > INT32_MAX) {
            ERROR(
                e,
                "integer literal exceeds i32 range; use a smaller literal and explicit arithmetic");
            return invalid();
        }
        return (Value){.type = C->builtins[TY_I32], .code = text, .literal = true, .integer = n};
    }
    case MINI_EXPR_BOOL:
        return (Value){.type = C->builtins[TY_BOOL],
                       .code = e->as.literal.kind == TOKEN_TRUE ? "true" : "false"};
    case MINI_EXPR_STRING: {
        Token t = e->as.literal;
        char *text = mini_palloc(C, t.length + 1);
        memcpy(text, U->source.text + t.start, t.length);
        return (Value){.type = mini_ppointer(C, C->builtins[TY_U8]),
                       .code = mini_pformat(C, "((uint8_t*)%s)", text),
                       .readonly = true};
    }
    case MINI_EXPR_IDENTIFIER: {
        Local *v = mini_plocal(b, mini_pname(C, U, e->as.identifier));
        if (!v) {
            ERROR(e, "unknown variable");
            return invalid();
        }
        if (!b->writing && !v->initialized) {
            ERROR(e, "read of uninitialized variable '%s'", v->name);
            return invalid();
        }
        return (Value){.type = v->type,
                       .code = v->code,
                       .place = true,
                       .readonly = v->readonly,
                       .local = v,
                       .immutable_place = v->immutable};
    }
    case MINI_EXPR_CAST: {
        Type *t = mini_presolve(C, b->env, e->as.builtin.type);
        Value v = mini_pexpr(b, e->as.builtin.value);
        if (!t || !v.type)
            return invalid();
        bool numeric = integer(t) && integer(v.type);
        bool pointers = (t->kind == TY_POINTER && v.type->kind == TY_POINTER);
        bool address = (t->kind == TY_USIZE && v.type->kind == TY_POINTER) ||
                       (t->kind == TY_POINTER && v.type->kind == TY_USIZE);
        if (!numeric && !pointers && !address) {
            ERROR(e, "unsupported explicit cast");
            return invalid();
        }
        /* Typed upcasts preserve a borrow. Reinterpreting raw storage must not
         * manufacture heap residency for a stack-only class (including aggregates). */
        if (t->kind == TY_POINTER && !mini_passignable(C, t, v.type) &&
            !mini_pheapable(C, U, (Token){.start = e->start}, t->element))
            return invalid();
        if (t->kind == TY_POINTER && v.literal && v.integer == 0) {
            ERROR(e, "zero address cannot form a pointer; use Optional.None");
            return invalid();
        }
        v.code = t->kind == TY_POINTER
                     ? mini_pformat(C, "((%s)mini_rt_checked_pointer((void*)(%s)))",
                                    mini_pctype(C, t), v.code)
                     : mini_pformat(C, "((%s)(%s))", mini_pctype(C, t), v.code);
        v.type = t;
        v.place = false;
        v.local = NULL;
        return v;
    }
    case MINI_EXPR_SIZEOF: {
        Type *t = mini_presolve(C, b->env, e->as.builtin.type);
        if (!storage(b, t, e))
            return invalid();
        return (Value){.type = C->builtins[TY_USIZE],
                       .code = mini_pformat(C, "sizeof(%s)", mini_pctype(C, t))};
    }
    case MINI_EXPR_CALL:
        return call(b, e);
    case MINI_EXPR_MEMBER:
        return member(b, e);
    case MINI_EXPR_UNARY: {
        bool old = b->writing;
        b->writing = e->as.unary.op.kind == TOKEN_AMP;
        Value v = mini_pexpr(b, e->as.unary.operand);
        b->writing = old;
        if (!v.type)
            return invalid();
        switch (e->as.unary.op.kind) {
        case TOKEN_AMP:
            if (v.immutable_place &&
                !(v.type->kind == TY_NOMINAL && v.type->ref.decl->kind == MINI_DECL_STRUCT &&
                  v.type->ref.decl->as.struct_decl.is_class)) {
                ERROR(e, "cannot take a mutable address of a val binding or field");
                return invalid();
            }
            if (v.local && v.local->immutable && !v.local->initialized) {
                ERROR(e, "cannot borrow an uninitialized val");
                return invalid();
            }
            if (!v.place || (v.local && v.local->is_this)) {
                ERROR(e, "address requires a writable place expression");
                return invalid();
            }
            return (Value){.type = mini_ppointer(C, v.type),
                           .code = mini_pformat(C, "(&(%s))", v.code),
                           .readonly = v.readonly};
        case TOKEN_STAR:
            if (v.type->kind != TY_POINTER) {
                ERROR(e, "dereference requires a pointer");
                return invalid();
            }
            return (Value){.type = v.type->element,
                           .code = mini_pformat(C, "(*(%s))", v.code),
                           .place = true,
                           .readonly = v.readonly,
                           .polymorphic_view =
                               v.type->element->kind == TY_NOMINAL &&
                               (v.type->element->ref.decl->flags & (MINI_OPEN | MINI_ABSTRACT))};
        case TOKEN_BANG:
            if (v.type->kind != TY_BOOL) {
                ERROR(e, "logical negation requires bool");
                return invalid();
            }
            return (Value){.type = v.type, .code = mini_pformat(C, "(!(%s))", v.code)};
        case TOKEN_MINUS:
            if (v.type->kind != TY_I32 && v.type->kind != TY_ISIZE) {
                ERROR(e, "negation requires a signed integer");
                return invalid();
            }
            return (Value){.type = v.type, .code = mini_pformat(C, "(-(%s))", v.code)};
        default:
            return invalid();
        }
    }
    case MINI_EXPR_INDEX: {
        Value v = mini_pexpr(b, e->as.index.target);
        if (v.type && v.type->kind == TY_POINTER && v.local && !v.local->initialized) {
            ERROR(e, "read of uninitialized pointer");
            return invalid();
        }
        bool writing = b->writing;
        b->writing = false;
        Value i = freeze(b, mini_pexpr(b, e->as.index.index));
        b->writing = writing;
        if (!v.type || !integer(i.type)) {
            ERROR(e, "index requires an integer");
            return invalid();
        }
        if (v.type->kind != TY_ARRAY && v.type->kind != TY_POINTER) {
            ERROR(e, "indexing requires an array or pointer");
            return invalid();
        }
        return (Value){.type = v.type->element,
                       .code = mini_pformat(C, "(%s)%s[%s]", v.code,
                                            v.type->kind == TY_ARRAY ? ".data" : "", i.code),
                       .place = v.type->kind == TY_POINTER || v.place,
                       .readonly = v.readonly};
    }
    case MINI_EXPR_ASSIGN: {
        bool old = b->writing;
        b->writing = true;
        Value left = mini_pexpr(b, e->as.assign.target);
        b->writing = old;
        if (!left.type)
            return invalid();
        if (left.local && left.local->immutable && !left.local->is_this) {
            if (left.local->assigned || left.local->loop_depth < b->loop_depth) {
                ERROR(e, "val can only be initialized once on every execution path");
                return invalid();
            }
        } else if (left.immutable_place && !(left.local && left.local->is_this)) {
            ERROR(e, "cannot reassign a val field");
            return invalid();
        }
        if (!left.place || left.readonly || (left.local && left.local->is_this)) {
            ERROR(e, "assignment requires a writable place");
            return invalid();
        }
        if (left.type->kind == TY_NOMINAL && left.type->ref.decl->kind == MINI_DECL_STRUCT &&
            (left.type->ref.decl->flags & (MINI_OPEN | MINI_ABSTRACT)) && !left.local) {
            ERROR(e, "cannot replace a polymorphic object through a base place");
            return invalid();
        }
        char *destination = mini_pformat(C, "tmp%zu", b->temporary++);
        mini_pwrite(C, b->out, "%s* %s = &(%s);\n", mini_pctype(C, left.type), destination,
                    left.code);
        Value right = coerce(b, mini_pexpected(b, e->as.assign.value, left.type), left.type, e);
        if (!right.type)
            return invalid();
        mini_pwrite(C, b->out, "*%s = %s;\n", destination, right.code);
        if (left.local) {
            left.local->initialized = true;
            left.local->assigned = true;
            left.local->readonly = right.readonly;
        }
        return (Value){.type = C->builtins[TY_VOID], .code = "0"};
    }
    case MINI_EXPR_BINARY: {
        TokenKind op = e->as.binary.op.kind;
        Value left = freeze(b, mini_pexpr(b, e->as.binary.left));
        if (!left.type)
            return invalid();
        if (op == TOKEN_AMP_AMP || op == TOKEN_PIPE_PIPE) {
            if (left.type->kind != TY_BOOL) {
                ERROR(e, "logical operands must be bool");
                return invalid();
            }
            char *tmp = mini_pformat(C, "tmp%zu", b->temporary++);
            mini_pwrite(C, b->out, "bool %s = %s;\nif (%s%s) {\n", tmp, left.code,
                        op == TOKEN_PIPE_PIPE ? "!" : "", tmp);
            Value right = mini_pexpr(b, e->as.binary.right);
            if (!right.type || right.type->kind != TY_BOOL) {
                ERROR(e, "logical operands must be bool");
                return invalid();
            }
            mini_pwrite(C, b->out, "%s = %s;\n}\n", tmp, right.code);
            return (Value){.type = left.type, .code = tmp};
        }
        Value right = freeze(b, mini_pexpr(b, e->as.binary.right));
        if (!right.type)
            return invalid();
        bool eq = op == TOKEN_EQUAL_EQUAL || op == TOKEN_BANG_EQUAL;
        if (left.type != right.type) {
            if (eq && mini_passignable(C, left.type, right.type))
                right = coerce(b, right, left.type, e);
            else if (eq && mini_passignable(C, right.type, left.type))
                left = coerce(b, left, right.type, e);
            else {
                ERROR(e, "binary operand types differ");
                return invalid();
            }
        }
        bool compare = eq || op == TOKEN_LESS || op == TOKEN_LESS_EQUAL || op == TOKEN_GREATER ||
                       op == TOKEN_GREATER_EQUAL;
        if (!integer(left.type) &&
            !(eq &&
              (left.type->kind == TY_BOOL || left.type->kind == TY_POINTER ||
               (left.type->kind == TY_NOMINAL && left.type->ref.decl->kind == MINI_DECL_ENUM)))) {
            ERROR(e, "invalid binary operand type");
            return invalid();
        }
        char *operator = mini_pname(C, U, e->as.binary.op);
        return (Value){.type = compare ? C->builtins[TY_BOOL] : left.type,
                       .code = mini_pformat(C, "((%s) %s (%s))", left.code, operator, right.code)};
    }
    case MINI_EXPR_LAMBDA:
        return mini_plambda(b, e);
    case MINI_EXPR_STRUCT: {
        MiniType ast = {.kind = MINI_TYPE_NAMED,
                        .as.name = e->as.struct_literal.type_name,
                        .args = e->type_args,
                        .arg_count = e->type_arg_count};
        Type *t = mini_presolve(C, b->env, &ast);
        if (!storage(b, t, e))
            return invalid();
        if (t->kind != TY_NOMINAL || t->ref.decl->kind != MINI_DECL_STRUCT) {
            ERROR(e, "record literal requires a class or struct");
            return invalid();
        }
        if (t->field_count != e->as.struct_literal.field_count) {
            ERROR(e, "incorrect number of fields");
            return invalid();
        }
        Buffer fields = {0};
        bool tagged = t->ref.decl->as.struct_decl.is_class;
        if (tagged)
            mini_pwrite(C, &fields, ".mini_tag = %zu, ", t->id);
        for (size_t i = 0; i < e->as.struct_literal.field_count && !C->result.message; i++) {
            MiniFieldInit *init = &e->as.struct_literal.fields[i];
            char *name = mini_pname(C, U, init->name);
            size_t j = 0;
            while (j < t->field_count && strcmp(t->fields[j].name, name))
                j++;
            if (j == t->field_count) {
                ERROR(e, "unknown field '%s'", name);
                return invalid();
            }
            for (size_t k = 0; k < i; k++)
                if (!strcmp(name, mini_pname(C, U, e->as.struct_literal.fields[k].name))) {
                    ERROR(e, "duplicate field");
                    return invalid();
                }
            if (!access(b, t->fields[j].flags, t->fields[j].owner, e))
                return invalid();
            Value v = freeze(b, coerce(b, mini_pexpected(b, init->value, t->fields[j].type),
                                       t->fields[j].type, init->value));
            if (!v.type)
                return invalid();
            if (v.readonly) {
                ERROR(e, "immutable string cannot escape into a mutable record field");
                return invalid();
            }
            mini_pwrite(C, &fields, ".field_%zu = %s, ", j, v.code);
        }
        return (Value){.type = t,
                       .code = mini_pformat(C, "((%s){%s})", mini_pctype(C, t),
                                            fields.length ? fields.data : "0")};
    }
    case MINI_EXPR_MATCH:
        return mini_pmatch(b, e);
    }
    ERROR(e, "unsupported expression");
    return invalid();
}

LocalState *mini_psnapshot(Body *b, size_t n) {
    LocalState *values = mini_palloc(C, n * sizeof(*values));
    for (size_t i = 0; i < n; i++)
        values[i] =
            (LocalState){b->locals[i]->initialized, b->locals[i]->assigned, b->locals[i]->readonly};
    return values;
}
void mini_prestore(Body *b, const LocalState *values, size_t n) {
    for (size_t i = 0; i < n; i++) {
        b->locals[i]->initialized = values[i].initialized;
        b->locals[i]->assigned = values[i].assigned;
        b->locals[i]->readonly = values[i].readonly;
    }
}
bool mini_pstatement(Body *b, const MiniStmt *s) {
    if (!s || C->result.message)
        return false;
    switch (s->kind) {
    case MINI_STMT_BLOCK: {
        size_t old = b->local_count;
        b->depth++;
        mini_pwrite(C, b->out, "{\n");
        bool returns = false;
        for (size_t i = 0; i < s->as.block.count && !C->result.message; i++) {
            bool r = mini_pstatement(b, s->as.block.items[i]);
            returns |= r;
        }
        mini_pwrite(C, b->out, "}\n");
        b->local_count = old;
        b->depth--;
        return returns;
    }
    case MINI_STMT_LET: {
        Type *t = s->as.let.type ? mini_presolve(C, b->env, s->as.let.type) : NULL;
        if (s->as.let.type && !t)
            return false;
        MiniExpr at = {.start = s->start};
        char *name = mini_pname(C, U, s->as.let.name);
        if (!strcmp(name, "this")) {
            mini_perror(C, U, s->as.let.name, "this is reserved for the current instance");
            return false;
        }
        Local *previous = mini_plocal(b, name);
        if (previous && previous->depth == b->depth) {
            mini_perror(C, U, s->as.let.name, "duplicate local variable");
            return false;
        }
        Value value = {0};
        if (s->as.let.value) {
            value = mini_pexpected(b, s->as.let.value, t);
            if (!value.type)
                return false;
            if (!t) {
                t = value.type;
                if (t->kind == TY_VOID) {
                    mini_perror(C, U, s->as.let.name, "cannot infer a value type from void");
                    return false;
                }
            }
            value = coerce(b, value, t, s->as.let.value);
        }
        if (!storage(b, t, &at))
            return false;
        Local *v = mini_palloc(C, sizeof(*v));
        v->name = name;
        v->code = mini_pformat(C, "local%zu", b->temporary++);
        v->type = t;
        v->depth = b->depth;
        v->initialized = value.type != NULL;
        v->assigned = v->initialized;
        v->immutable = s->as.let.immutable;
        v->loop_depth = b->loop_depth;
        v->readonly = value.readonly;
        mini_pappend(C, (void **)&b->locals, &b->local_count, sizeof(v), &v);
        mini_pwrite(C, b->out, "%s %s%s%s;\n", mini_pctype(C, t), v->code, value.type ? " = " : "",
                    value.type ? value.code : "");
        return false;
    }
    case MINI_STMT_RETURN: {
        if (s->as.ret.value) {
            Value value = coerce(b, mini_pexpected(b, s->as.ret.value, b->function->result),
                                 b->function->result, s->as.ret.value);
            if (value.type && value.type->kind == TY_VOID &&
                b->function->ref.decl->as.function.expression_body)
                mini_pwrite(C, b->out, "return;\n");
            else if (value.type && value.type->kind != TY_VOID)
                mini_pwrite(C, b->out, "return %s;\n", value.code);
            else
                mini_perror(C, U, (Token){.start = s->start},
                            "return requires a value of the function return type");
        } else if (b->function->result->kind != TY_VOID)
            mini_perror(C, U, (Token){.start = s->start}, "non-void function must return a value");
        else
            mini_pwrite(C, b->out, "return;\n");
        return true;
    }
    case MINI_STMT_EXPR:
        mini_pexpr(b, s->as.expr);
        return false;
    case MINI_STMT_IF: {
        mini_pwrite(C, b->out, "{\n");
        Value condition = freeze(b, mini_pexpr(b, s->as.if_stmt.condition));
        if (!condition.type || condition.type->kind != TY_BOOL) {
            mini_perror(C, U, (Token){.start = s->start}, "if condition must be bool");
            return false;
        }
        size_t n = b->local_count;
        LocalState *before = mini_psnapshot(b, n);
        mini_pwrite(C, b->out, "if (%s) {\n", condition.code);
        bool yes = mini_pstatement(b, s->as.if_stmt.then_branch);
        mini_pwrite(C, b->out, "}\n");
        LocalState *after = mini_psnapshot(b, n);
        mini_prestore(b, before, n);
        bool no = false;
        if (s->as.if_stmt.else_branch) {
            mini_pwrite(C, b->out, "else {\n");
            no = mini_pstatement(b, s->as.if_stmt.else_branch);
            mini_pwrite(C, b->out, "}\n");
        }
        for (size_t i = 0; i < n; i++) {
            LocalState other = {b->locals[i]->initialized, b->locals[i]->assigned,
                                b->locals[i]->readonly};
            LocalState merged;
            if (yes && !no)
                merged = other;
            else if (no && !yes)
                merged = after[i];
            else
                merged = (LocalState){after[i].initialized && other.initialized,
                                      after[i].assigned || other.assigned,
                                      after[i].readonly || other.readonly};
            b->locals[i]->initialized = merged.initialized;
            b->locals[i]->assigned = merged.assigned;
            b->locals[i]->readonly = merged.readonly;
        }
        mini_pwrite(C, b->out, "}\n");
        return yes && no;
    }
    case MINI_STMT_WHILE: {
        size_t n = b->local_count;
        LocalState *before = mini_psnapshot(b, n);
        mini_pwrite(C, b->out, "while (true) {\n");
        Value condition = freeze(b, mini_pexpr(b, s->as.while_stmt.condition));
        if (!condition.type || condition.type->kind != TY_BOOL) {
            mini_perror(C, U, (Token){.start = s->start}, "while condition must be bool");
            return false;
        }
        mini_pwrite(C, b->out, "if (!(%s)) break;\n", condition.code);
        b->loop_depth++;
        mini_pstatement(b, s->as.while_stmt.body);
        b->loop_depth--;
        mini_pwrite(C, b->out, "}\n");
        LocalState *after = mini_psnapshot(b, n);
        mini_prestore(b, before, n);
        for (size_t i = 0; i < n; i++) {
            b->locals[i]->assigned |= after[i].assigned;
            b->locals[i]->readonly |= after[i].readonly;
        }
        return false;
    }
    }
    return false;
}
