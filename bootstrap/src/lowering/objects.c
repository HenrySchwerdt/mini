#include "project_internal.h"
#include <string.h>

/* Factories are specialized for the receiving concrete class, not inherited
 * static functions whose Self would accidentally allocate only the base layout. */
Value mini_pobject_call(Body *b, const MiniExpr *e, Type *owner, const char *name,
                        bool *recognized) {
    Context *c = b->c;
    Unit *u = b->env.unit;
    Token at = {.start = e->start};
    *recognized = false;
    if (strcmp(name, "allocate") && strcmp(name, "free"))
        return (Value){0};
    if (owner->kind != TY_NOMINAL || owner->ref.decl->kind != MINI_DECL_STRUCT ||
        !owner->ref.decl->as.struct_decl.is_class)
        return (Value){0};
    /* Ordinary stack-only classes may still define unrelated methods of these names. */
    if (mini_pmethod(owner, name))
        return (Value){0};
    *recognized = true;
    if (!mini_psubtype(c, owner, c->allocatable_type)) {
        mini_perror(c, u, at, "object allocation requires an AllocatableObject subclass");
        return (Value){0};
    }
    if (!mini_pheapable(c, u, at, owner))
        return (Value){0};
    const MiniExpr *callee = e->as.call.callee;
    size_t count = e->as.call.args.count;
    if (callee->type_arg_count || (count != 1 && count != 2)) {
        mini_perror(c, u, at, "allocate/free require a value/pointer and an optional allocator");
        return (Value){0};
    }
    bool release = !strcmp(name, "free");
    Type *pointer = mini_ppointer(c, owner);
    Value value = mini_pexpected(b, e->as.call.args.items[0], release ? pointer : owner);
    if (!value.type || c->result.message)
        return (Value){0};
    if (value.polymorphic_view || value.type != (release ? pointer : owner)) {
        mini_perror(c, u, at, "allocate/free require the exact concrete class value/pointer");
        return (Value){0};
    }
    char *input = mini_pformat(c, "tmp%zu", b->temporary++);
    mini_pwrite(c, b->out, "%s %s = %s;\n", mini_pctype(c, value.type), input, value.code);

    Type *allocator_type = NULL;
    Method *operation = NULL;
    char *allocator = NULL;
    if (count == 2) {
        Ref ref = mini_plookup(c, u, "mini.memory.Allocator", at);
        if (!ref.decl)
            return (Value){0};
        allocator_type = mini_pnominal(c, ref, NULL, 0);
        if (!mini_pensure(c, allocator_type) ||
            allocator_type->ref.decl->kind != MINI_DECL_STRUCT ||
            !allocator_type->ref.decl->as.struct_decl.is_interface) {
            mini_perror(c, u, at, "mini.memory.Allocator must be an interface");
            return (Value){0};
        }
        Value a = mini_pexpr(b, e->as.call.args.items[1]);
        Type *expected = mini_ppointer(c, allocator_type);
        if (!a.type || !mini_passignable(c, expected, a.type) || a.readonly) {
            mini_perror(c, u, at, "allocator argument must be a borrowed *mini.memory.Allocator");
            return (Value){0};
        }
        allocator = mini_pformat(c, "tmp%zu", b->temporary++);
        mini_pwrite(c, b->out, "%s %s = (%s)%s;\nif (!%s) abort();\n", mini_pctype(c, expected),
                    allocator, mini_pctype(c, expected), a.code, allocator);
        operation = mini_pmethod(allocator_type, release ? "release" : "allocate");
        if (!operation || !operation->open || operation->param_count != 2 ||
            operation->params[1] !=
                (release ? mini_ppointer(c, c->builtins[TY_U8]) : c->builtins[TY_USIZE]) ||
            (release && operation->result != c->builtins[TY_VOID])) {
            mini_perror(c, u, at, "invalid Allocator allocate/release contract");
            return (Value){0};
        }
    }
    if (release) {
        if (allocator)
            mini_pwrite(c, b->out, "mini_dispatch_%zu_%zu(%s, (uint8_t*)%s);\n", allocator_type->id,
                        (size_t)(operation - allocator_type->methods), allocator, input);
        else
            mini_pwrite(c, b->out, "mini_rt_free((uint8_t*)%s);\n", input);
        return (Value){.type = c->builtins[TY_VOID], .code = "0"};
    }
    Ref error_ref = mini_plookup(c, u, "mini.memory.AllocationError", at);
    if (!error_ref.decl)
        return (Value){0};
    if (error_ref.decl->kind != MINI_DECL_ENUM || error_ref.decl->generic_count) {
        mini_perror(c, u, at, "AllocationError must be a non-generic enum");
        return (Value){0};
    }
    Type *error = mini_pnominal(c, error_ref, NULL, 0);
    size_t oom = 0;
    while (oom < error_ref.decl->as.enum_decl.variant_count &&
           strcmp(mini_pname(c, error_ref.unit, error_ref.decl->as.enum_decl.variants[oom]),
                  "OutOfMemory"))
        oom++;
    if (oom == error_ref.decl->as.enum_decl.variant_count) {
        mini_perror(c, u, at, "AllocationError requires OutOfMemory");
        return (Value){0};
    }
    Type **args = mini_palloc(c, 2 * sizeof(*args));
    args[0] = pointer;
    args[1] = error;
    Type *result = mini_pvariant(c, TY_RESULT, args, 2);
    char *output = mini_pformat(c, "tmp%zu", b->temporary++);
    char *raw = mini_pformat(c, "tmp%zu", b->temporary++);
    mini_pwrite(c, b->out, "%s %s = {.tag=1,.data.error=%zu};\n", mini_pctype(c, result), output,
                oom);
    if (allocator) {
        Type *r = operation->result;
        if (r->kind != TY_RESULT || r->args[0] != mini_ppointer(c, c->builtins[TY_U8]) ||
            r->args[1] != error) {
            mini_perror(c, u, at, "Allocator.allocate must return Result<*u8, AllocationError>");
            return (Value){0};
        }
        char *allocation = mini_pformat(c, "tmp%zu", b->temporary++);
        mini_pwrite(c, b->out, "%s %s = mini_dispatch_%zu_%zu(%s, sizeof(%s));\n",
                    mini_pctype(c, r), allocation, allocator_type->id,
                    (size_t)(operation - allocator_type->methods), allocator,
                    mini_pctype(c, owner));
        mini_pwrite(c, b->out,
                    "uint8_t* %s = %s.tag == 0 ? %s.data.value : NULL;\n"
                    "if (%s.tag != 0) %s.data.error = %s.data.error;\n",
                    raw, allocation, allocation, allocation, output, allocation);
    } else
        mini_pwrite(c, b->out, "uint8_t* %s = mini_rt_alloc(sizeof(%s));\n", raw,
                    mini_pctype(c, owner));
    mini_pwrite(c, b->out, "if (%s) { *((%s)%s) = %s; %s.tag = 0; %s.data.value = (%s)%s; }\n", raw,
                mini_pctype(c, pointer), raw, input, output, output, mini_pctype(c, pointer), raw);
    return (Value){.type = result, .code = output};
}
