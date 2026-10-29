#include "project_internal.h"
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

/* MSVC's C mode has no max_align_t; a union of the widest scalar types gives the same alignment. */
struct Allocation {
    Allocation *next;
    union {
        long double long_double;
        long long long_long;
        void *pointer;
        void (*function)(void);
    } alignment;
};

void *mini_palloc(Context *c, size_t size) {
    Allocation *a = size <= SIZE_MAX - sizeof(Allocation) ? calloc(1, sizeof(*a) + size) : NULL;
    if (!a) {
        if (!c->result.message) {
            c->result.message = malloc(14);
            if (c->result.message)
                memcpy(c->result.message, "out of memory", 14);
        }
        longjmp(c->allocation_failure, 1);
    }
    a->next = c->allocations;
    c->allocations = a;
    return a + 1;
}
static char *vformat(Context *c, const char *format, va_list args) {
    va_list copy;
    va_copy(copy, args);
    int n = vsnprintf(NULL, 0, format, copy);
    va_end(copy);
    if (n < 0)
        longjmp(c->allocation_failure, 1);
    char *s = mini_palloc(c, (size_t)n + 1);
    if (s)
        vsnprintf(s, (size_t)n + 1, format, args);
    return s;
}
char *mini_pformat(Context *c, const char *format, ...) {
    va_list args;
    va_start(args, format);
    char *s = vformat(c, format, args);
    va_end(args);
    return s;
}
void mini_pappend(Context *c, void **items, size_t *count, size_t size, const void *value) {
    if (*count >= SIZE_MAX / size - 1) {
        mini_perror(c, NULL, (Token){0}, "collection too large");
        return;
    }
    void *next = mini_palloc(c, (*count + 1) * size);
    if (!next)
        return;
    if (*count)
        memcpy(next, *items, *count * size);
    memcpy((char *)next + *count * size, value, size);
    *items = next;
    (*count)++;
}
void mini_pwrite(Context *c, Buffer *b, const char *format, ...) {
    if (c->result.message)
        return;
    va_list args;
    va_start(args, format);
    char *s = vformat(c, format, args);
    va_end(args);
    if (!s)
        return;
    size_t n = strlen(s);
    if (n > SIZE_MAX - b->length - 1) {
        mini_perror(c, NULL, (Token){0}, "output too large");
        return;
    }
    if (b->length + n + 1 > b->capacity) {
        size_t cap = b->length + n + 1;
        if (cap < SIZE_MAX / 2)
            cap *= 2;
        char *next = mini_palloc(c, cap);
        if (!next)
            return;
        if (b->length)
            memcpy(next, b->data, b->length);
        b->data = next;
        b->capacity = cap;
    }
    memcpy(b->data + b->length, s, n + 1);
    b->length += n;
}
void mini_perror(Context *c, Unit *u, Token token, const char *format, ...) {
    if (c->result.message)
        return;
    va_list args;
    va_start(args, format);
    char *s = vformat(c, format, args);
    va_end(args);
    if (!s)
        return;
    c->result.message = malloc(strlen(s) + 1);
    if (!c->result.message)
        longjmp(c->allocation_failure, 1);
    strcpy(c->result.message, s);
    c->result.source_index = u ? u->index : 0;
    c->result.line = 1;
    c->result.column = 1;
    if (u)
        for (size_t i = 0; i < token.start && u->source.text[i]; i++) {
            if (u->source.text[i] == '\n') {
                c->result.line++;
                c->result.column = 1;
            } else
                c->result.column++;
        }
}
char *mini_pname(Context *c, Unit *u, Token token) {
    char *s = mini_palloc(c, token.length + 1);
    if (!s)
        return NULL;
    /* Joining lexer tokens normalizes whitespace/comments in qualified names. */
    Lexer lexer = lexer_new(u->source.text, token.start + token.length);
    lexer.position = token.start;
    size_t n = 0;
    for (Token t = lexer_next(&lexer); t.kind != TOKEN_EOF; t = lexer_next(&lexer)) {
        if (t.kind == TOKEN_ERROR)
            break;
        memcpy(s + n, u->source.text + t.start, t.length);
        n += t.length;
    }
    s[n] = 0;
    return s;
}
static const char *module_name(Unit *u) {
    return u->source.module ? u->source.module : "main";
}
static bool same_module(Unit *a, Unit *b) {
    return !strcmp(module_name(a), module_name(b));
}
static bool dependency(Context *c, Unit *from, Unit *to) {
    if (same_module(from, to))
        return true;
    for (size_t i = 0; i < c->module_count; i++) {
        const MiniModule *m = &c->modules[i];
        if (!strcmp(m->name, module_name(from)))
            for (size_t j = 0; j < m->dependency_count; j++)
                if (!strcmp(m->dependencies[j], module_name(to)))
                    return true;
    }
    return false;
}
static bool visible(Context *c, Unit *from, Ref ref) {
    if (!dependency(c, from, ref.unit))
        return false;
    if ((ref.decl->flags & MINI_PRIVATE) && from != ref.unit)
        return false;
    if ((ref.decl->flags & MINI_INTERNAL) && !same_module(from, ref.unit))
        return false;
    return true;
}
static Ref qualified(Context *c, Unit *from, const char *name) {
    Ref found = {0}, hidden = {0};
    for (size_t i = 0; i < c->unit_count; i++) {
        Unit *u = &c->units[i];
        for (size_t j = 0; j < u->ast->count; j++) {
            const MiniDecl *d = u->ast->items[j];
            char *short_name = mini_pname(c, u, d->name);
            char *full =
                *u->package ? mini_pformat(c, "%s.%s", u->package, short_name) : short_name;
            if (!strcmp(full, name)) {
                if (d->flags & MINI_PRIVATE) {
                    if (u == from)
                        return (Ref){u, d};
                    hidden = (Ref){u, d};
                } else
                    found = (Ref){u, d};
            }
        }
    }
    return found.decl ? found : hidden;
}
static Ref lookup(Context *c, Unit *u, const char *name, Token at) {
    if (!strcmp(name, "Object") || !strcmp(name, "mini.core.Object"))
        return c->object_type->ref;
    if (!strcmp(name, "AllocatableObject") || !strcmp(name, "mini.core.AllocatableObject"))
        return c->allocatable_type->ref;
    Ref found = {0};
    if (strchr(name, '.')) {
        found = qualified(c, u, name);
    } else {
        char *local = *u->package ? mini_pformat(c, "%s.%s", u->package, name) : (char *)name;
        Ref own = qualified(c, u, local);
        Ref explicit = {0};
        for (size_t i = 0; i < u->ast->import_count; i++) {
            MiniImport *im = &u->ast->imports[i];
            if (im->star)
                continue;
            char *path = mini_pname(c, u, im->path);
            char *alias = im->alias.length ? mini_pname(c, u, im->alias) : strrchr(path, '.');
            if (!im->alias.length)
                alias = alias ? alias + 1 : path;
            if (!strcmp(alias, name)) {
                Ref candidate = qualified(c, u, path);
                if (explicit.decl && explicit.decl != candidate.decl) {
                    mini_perror(c, u, at, "ambiguous explicit import '%s'", name);
                    return (Ref){0};
                }
                explicit = candidate;
            }
        }
        if (own.decl && explicit.decl && own.decl != explicit.decl) {
            mini_perror(c, u, at, "import conflicts with declaration '%s'", name);
            return (Ref){0};
        }
        found = explicit.decl ? explicit : own;
        if (!found.decl)
            for (size_t i = 0; i < u->ast->import_count; i++) {
                MiniImport *im = &u->ast->imports[i];
                if (!im->star)
                    continue;
                char *full = mini_pformat(c, "%s.%s", mini_pname(c, u, im->path), name);
                Ref candidate = qualified(c, u, full);
                if (!candidate.decl || !visible(c, u, candidate))
                    continue;
                if (found.decl && found.decl != candidate.decl) {
                    mini_perror(c, u, at, "ambiguous star import '%s'", name);
                    return (Ref){0};
                }
                found = candidate;
            }
    }
    if (!found.decl)
        mini_perror(c, u, at, "unknown declaration '%s'", name);
    else if (!visible(c, u, found)) {
        mini_perror(c, u, at, "declaration '%s' is not visible from module '%s'", name,
                    module_name(u));
        return (Ref){0};
    }
    return found;
}
static Type *new_type(Context *c, TypeKind kind) {
    if (c->type_count >= 4096) {
        mini_perror(c, NULL, (Token){0}, "type instantiation limit exceeded");
        return NULL;
    }
    Type *t = mini_palloc(c, sizeof(*t));
    if (!t)
        return NULL;
    t->kind = kind;
    t->id = c->type_count;
    mini_pappend(c, (void **)&c->types, &c->type_count, sizeof(t), &t);
    return t;
}
static Type *pointer(Context *c, Type *element) {
    if (!element)
        return NULL;
    for (size_t i = 0; i < c->type_count; i++)
        if (c->types[i]->kind == TY_POINTER && c->types[i]->element == element)
            return c->types[i];
    Type *t = new_type(c, TY_POINTER);
    if (t)
        t->element = element;
    return t;
}
static Type *nominal(Context *c, Ref ref, Type **args, size_t count) {
    if (ref.decl->generic_count != count) {
        mini_perror(c, ref.unit, ref.decl->name, "type '%s' expects %zu type arguments, got %zu",
                    mini_pname(c, ref.unit, ref.decl->name), ref.decl->generic_count, count);
        return NULL;
    }
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        if (t->kind != TY_NOMINAL || t->ref.decl != ref.decl || t->arg_count != count)
            continue;
        size_t j = 0;
        while (j < count && t->args[j] == args[j])
            j++;
        if (j == count)
            return t;
    }
    Type *t = new_type(c, TY_NOMINAL);
    if (t) {
        t->ref = ref;
        t->args = args;
        t->arg_count = count;
    }
    return t;
}
Type *mini_pvariant(Context *c, TypeKind kind, Type **args, size_t count) {
    if (count != (kind == TY_OPTIONAL ? 1u : 2u)) {
        mini_perror(c, NULL, (Token){0}, "incorrect native type argument count");
        return NULL;
    }
    for (size_t i = 0; i < count; i++)
        if (!args[i] || args[i]->kind == TY_VOID) {
            mini_perror(c, NULL, (Token){0}, "variant payload requires a value type");
            return NULL;
        }
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        if (t->kind != kind || t->arg_count != count)
            continue;
        size_t j = 0;
        while (j < count && t->args[j] == args[j])
            j++;
        if (j == count)
            return t;
    }
    Type *t = new_type(c, kind);
    if (t) {
        t->args = args;
        t->arg_count = count;
    }
    return t;
}
Type *mini_pfunctype(Context *c, Type **params, size_t count, Type *result) {
    if (!result || !mini_pstorage(c, NULL, (Token){0}, result, true))
        return NULL;
    for (size_t i = 0; i < count; i++)
        if (!params[i] || !mini_pstorage(c, NULL, (Token){0}, params[i], false))
            return NULL;
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        if (t->kind != TY_FUNCTION || t->arg_count != count || t->element != result)
            continue;
        size_t j = 0;
        while (j < count && t->args[j] == params[j])
            j++;
        if (j == count)
            return t;
    }
    Type *t = new_type(c, TY_FUNCTION);
    if (t) {
        t->args = params;
        t->arg_count = count;
        t->element = result;
    }
    return t;
}
Environment mini_penv(Context *c, Ref ref, Type *owner, Type **args, size_t count) {
    Environment env = {.self = owner, .unit = ref.unit};
    if (owner)
        for (size_t i = 0; i < owner->arg_count; i++) {
            Binding binding = {owner->ref.decl->generics[i].name, owner->args[i], owner->ref.unit};
            mini_pappend(c, (void **)&env.items, &env.count, sizeof(binding), &binding);
        }
    for (size_t i = 0; i < count; i++) {
        Binding binding = {ref.decl->generics[i].name, args[i], ref.unit};
        mini_pappend(c, (void **)&env.items, &env.count, sizeof(binding), &binding);
    }
    return env;
}
Type *mini_presolve(Context *c, Environment env, const MiniType *ast) {
    if (!ast || c->result.message)
        return NULL;
    if (++c->instantiation_depth > 128) {
        mini_perror(c, env.unit, (Token){.start = ast->start}, "type nesting limit exceeded");
        c->instantiation_depth--;
        return NULL;
    }
    Type *t = NULL;
    if (ast->kind == MINI_TYPE_FUNCTION) {
        Type **params = mini_palloc(c, ast->arg_count * sizeof(*params));
        for (size_t i = 0; i < ast->arg_count; i++)
            params[i] = mini_presolve(c, env, ast->args[i]);
        t = mini_pfunctype(c, params, ast->arg_count,
                           mini_presolve(c, env, ast->as.function.result));
    } else if (ast->kind == MINI_TYPE_POINTER)
        t = pointer(c, mini_presolve(c, env, ast->as.pointer.element));
    else if (ast->kind == MINI_TYPE_ARRAY) {
        Type *element = mini_presolve(c, env, ast->as.array.element);
        char *size = mini_pname(c, env.unit, ast->as.array.length);
        char *end = NULL;
        unsigned long long n = strtoull(size, &end, 10);
        if (!element || !end || *end || n == 0 || n > 1048576)
            mini_perror(c, env.unit, ast->as.array.length, "invalid or excessive array length");
        else {
            for (size_t i = 0; i < c->type_count; i++)
                if (c->types[i]->kind == TY_ARRAY && c->types[i]->element == element &&
                    c->types[i]->length == n)
                    t = c->types[i];
            if (!t) {
                t = new_type(c, TY_ARRAY);
                t->element = element;
                t->length = (size_t)n;
            }
        }
    } else {
        char *name = mini_pname(c, env.unit, ast->as.name);
        static const char *names[] = {"void", "i32", "u8", "usize", "isize", "bool"};
        for (size_t i = 0; i < 6; i++)
            if (!strcmp(name, names[i]))
                t = c->builtins[i];
        if (!strcmp(name, "Self"))
            t = env.self;
        for (size_t i = env.count; !t && i > 0; i--) {
            Binding binding = env.items[i - 1];
            if (!strcmp(name, mini_pname(c, binding.unit, binding.name)))
                t = binding.value;
        }
        if (t && ast->arg_count) {
            mini_perror(c, env.unit, ast->as.name, "type does not accept generic arguments");
            t = NULL;
        }
        bool native = !strcmp(name, "Optional") || !strcmp(name, "Result");
        if (native && !c->result.message) {
            Type **args = mini_palloc(c, ast->arg_count * sizeof(Type *));
            for (size_t i = 0; i < ast->arg_count; i++)
                args[i] = mini_presolve(c, env, ast->args[i]);
            t = mini_pvariant(c, !strcmp(name, "Optional") ? TY_OPTIONAL : TY_RESULT, args,
                              ast->arg_count);
        }
        if (!t && !native && !c->result.message) {
            Ref ref = lookup(c, env.unit, name, ast->as.name);
            if (ref.decl && ref.decl->kind == MINI_DECL_FUNCTION)
                mini_perror(c, env.unit, ast->as.name, "function is not a type");
            else if (ref.decl) {
                Type **args = mini_palloc(c, ast->arg_count * sizeof(*args));
                for (size_t i = 0; i < ast->arg_count; i++)
                    args[i] = mini_presolve(c, env, ast->args[i]);
                if (!c->result.message) {
                    mini_pdeprecated(c, env.unit, ast->as.name, ref);
                    t = nominal(c, ref, args, ast->arg_count);
                }
            }
        }
    }
    c->instantiation_depth--;
    return t;
}
Method *mini_pmethod(Type *t, const char *name) {
    for (size_t i = 0; i < t->method_count; i++)
        if (!strcmp(t->methods[i].name, name))
            return &t->methods[i];
    return NULL;
}
static bool subtype(Context *c, Type *child, Type *parent, size_t depth) {
    if (child == parent)
        return true;
    if (!child || child->kind != TY_NOMINAL || depth > 128)
        return false;
    if (!mini_pensure(c, child))
        return false;
    for (size_t i = 0; i < child->super_count; i++)
        if (subtype(c, child->supers[i], parent, depth + 1))
            return true;
    return false;
}
bool mini_passignable(Context *c, Type *expected, Type *actual) {
    if (expected == actual)
        return true;
    if (!expected || !actual)
        return false;
    if (expected->kind == TY_POINTER && actual->kind == TY_POINTER)
        return subtype(c, actual->element, expected->element, 0);
    return false;
}
static bool signature(Method *a, Method *b) {
    if ((a->flags & MINI_STATIC) != (b->flags & MINI_STATIC) || a->param_count != b->param_count ||
        a->result != b->result)
        return false;
    for (size_t i = (a->flags & MINI_STATIC) ? 0 : 1; i < a->param_count; i++)
        if (a->params[i] != b->params[i])
            return false;
    return true;
}
static bool legal_visibility(Context *c, Ref ref, bool member) {
    unsigned f = ref.decl->flags;
    unsigned visibility = f & (MINI_PRIVATE | MINI_PROTECTED | MINI_INTERNAL | MINI_PUBLIC);
    if (visibility && (visibility & (visibility - 1)))
        mini_perror(c, ref.unit, ref.decl->name, "conflicting visibility modifiers");
    if (!member && (f & MINI_PROTECTED))
        mini_perror(c, ref.unit, ref.decl->name, "protected is not allowed at top level");
    if ((f & MINI_FINAL) && (f & (MINI_OPEN | MINI_ABSTRACT)))
        mini_perror(c, ref.unit, ref.decl->name, "conflicting final/open/abstract modifiers");
    return !c->result.message;
}
static bool constraints(Context *c, Environment env, Ref ref, Type **args, size_t count) {
    for (size_t i = 0; i < count; i++) {
        if (!ref.decl->generics[i].bound)
            continue;
        Type *bound = mini_presolve(c, env, ref.decl->generics[i].bound);
        if (!bound || !mini_pensure(c, bound) || bound->kind != TY_NOMINAL ||
            bound->ref.decl->kind != MINI_DECL_STRUCT ||
            (!bound->ref.decl->as.struct_decl.is_interface &&
             !bound->ref.decl->as.struct_decl.is_class) ||
            !subtype(c, args[i], bound, 0)) {
            mini_perror(c, ref.unit, ref.decl->generics[i].name,
                        "generic argument does not satisfy class/interface bound");
            return false;
        }
    }
    return !c->result.message;
}
bool mini_pensure(Context *c, Type *t) {
    if (!t || c->result.message)
        return false;
    if (t->kind != TY_NOMINAL || t->ref.decl->kind == MINI_DECL_ENUM)
        return true;
    if (t->state == 2)
        return true;
    if (t->state == 1) {
        mini_perror(c, t->ref.unit, t->ref.decl->name, "cyclic inheritance");
        return false;
    }
    t->state = 1;
    const MiniDecl *d = t->ref.decl;
    Unit *u = t->ref.unit;
    if (++c->instantiation_depth > 128) {
        mini_perror(c, u, d->name, "instantiation depth exceeded");
        c->instantiation_depth--;
        return false;
    }
    Environment env = mini_penv(c, t->ref, t, t->args, t->arg_count);
    if (!legal_visibility(c, t->ref, false))
        goto done;
    if (!mini_pannotations(c, t->ref, d->annotations, d->annotation_count))
        goto done;
    for (size_t i = 0; i < d->as.struct_decl.super_count && !c->result.message; i++) {
        Type *base = mini_presolve(c, env, d->as.struct_decl.supers[i]);
        if (!base || base->kind != TY_NOMINAL || base->ref.decl->kind != MINI_DECL_STRUCT) {
            mini_perror(c, u, d->name, "supertype must be a class or interface");
            break;
        }
        if (!mini_pensure(c, base))
            break;
        if (!base->ref.decl->as.struct_decl.is_interface) {
            if (!base->ref.decl->as.struct_decl.is_class || d->as.struct_decl.is_interface ||
                !d->as.struct_decl.is_class || t->base) {
                mini_perror(c, u, d->name, "only one class superclass is allowed");
                break;
            }
            if (!(base->ref.decl->flags & (MINI_OPEN | MINI_ABSTRACT))) {
                mini_perror(c, u, d->name, "cannot inherit from final class");
                break;
            }
            t->base = base;
            for (size_t j = 0; j < base->field_count; j++)
                mini_pappend(c, (void **)&t->fields, &t->field_count, sizeof(Field),
                             &base->fields[j]);
        }
        for (size_t j = 0; j < t->super_count; j++)
            if (t->supers[j] == base)
                mini_perror(c, u, d->name, "duplicate supertype");
        mini_pappend(c, (void **)&t->supers, &t->super_count, sizeof(base), &base);
        for (size_t j = 0; j < base->method_count; j++) {
            Method candidate = base->methods[j];
            if (candidate.flags & MINI_PRIVATE)
                continue;
            Method *old = mini_pmethod(t, candidate.name);
            if (!old)
                mini_pappend(c, (void **)&t->methods, &t->method_count, sizeof(candidate),
                             &candidate);
            else if (!signature(old, &candidate))
                mini_perror(c, u, d->name, "incompatible inherited method '%s'", candidate.name);
            else if (old->ref.decl != candidate.ref.decl) {
                if (old->abstract && !candidate.abstract) {
                    bool conflict = old->owner->ref.decl->as.struct_decl.is_interface &&
                                    candidate.owner->ref.decl->as.struct_decl.is_interface;
                    *old = candidate;
                    old->conflict = conflict;
                } else if (old->owner->ref.decl->as.struct_decl.is_interface &&
                           candidate.owner->ref.decl->as.struct_decl.is_interface)
                    old->conflict = true;
            }
        }
    }
    if (d->as.struct_decl.is_class && !t->base && t != c->object_type) {
        if (!mini_pensure(c, c->object_type))
            goto done;
        t->base = c->object_type;
        Type *root = c->object_type;
        mini_pappend(c, (void **)&t->supers, &t->super_count, sizeof(root), &root);
    }
    for (size_t i = 0; i < d->as.struct_decl.field_count && !c->result.message; i++) {
        const MiniParameter *ast = &d->as.struct_decl.fields[i];
        mini_pannotations(c, t->ref, ast->annotations, ast->annotation_count);
        Field f = {mini_pname(c, u, ast->name), mini_presolve(c, env, ast->type), ast->flags, t};
        mini_pstorage(c, u, ast->name, f.type, false);
        if (!f.type || f.type->kind == TY_VOID) {
            mini_perror(c, u, ast->name, "field requires a value type");
            break;
        }
        for (size_t j = 0; j < t->field_count; j++)
            if (!strcmp(f.name, t->fields[j].name))
                mini_perror(c, u, ast->name, "duplicate or inherited field '%s'", f.name);
        mini_pappend(c, (void **)&t->fields, &t->field_count, sizeof(f), &f);
    }
    for (size_t i = 0; i < d->as.struct_decl.method_count && !c->result.message; i++) {
        const MiniDecl *m = d->as.struct_decl.methods[i];
        Ref ref = {u, m};
        bool allocatable = false;
        for (Type *base = t; base; base = base->base)
            if (base == c->allocatable_type)
                allocatable = true;
        char *method_name = mini_pname(c, u, m->name);
        if (allocatable && (!strcmp(method_name, "allocate") || !strcmp(method_name, "free"))) {
            mini_perror(c, u, m->name, "allocate/free are reserved allocation factories");
            break;
        }
        if (!legal_visibility(c, ref, true))
            break;
        if (!mini_pannotations(c, ref, m->annotations, m->annotation_count))
            break;
        bool is_static = (m->flags & MINI_STATIC) != 0;
        if (is_static && (d->as.struct_decl.is_interface ||
                          (m->flags & (MINI_OPEN | MINI_ABSTRACT | MINI_OVERRIDE)))) {
            mini_perror(c, u, m->name,
                        "static methods cannot be virtual, abstract or interface members");
            break;
        }
        if (m->generic_count && !is_static &&
            (d->as.struct_decl.is_interface || (m->flags & (MINI_OPEN | MINI_ABSTRACT | MINI_OVERRIDE)))) {
            mini_perror(c, u, m->name,
                        "generic instance methods cannot be open, abstract, override or interface members");
            break;
        }
        Method method = {.flags = m->flags,
                         .name = mini_pname(c, u, m->name),
                         .ref = ref,
                         .owner = t,
                         .param_count = m->as.function.param_count + (is_static ? 0 : 1)};
        method.params = mini_palloc(c, method.param_count * sizeof(Type *));
        size_t first = is_static ? 0 : 1;
        if (first)
            method.params[0] = pointer(c, t);
        if (!m->generic_count) {
            for (size_t j = first; j < method.param_count; j++)
                method.params[j] = mini_presolve(c, env, m->as.function.params[j - first].type);
            method.result = m->as.function.return_type
                                ? mini_presolve(c, env, m->as.function.return_type)
                                : c->builtins[TY_VOID];
        }
        method.abstract = (m->flags & MINI_ABSTRACT) != 0;
        method.open = d->as.struct_decl.is_interface ||
                      (m->flags & (MINI_OPEN | MINI_ABSTRACT | MINI_OVERRIDE));
        if (m->flags & MINI_FINAL)
            method.open = false;
        for (size_t j = 0; j < m->as.function.param_count; j++)
            if (!strcmp(mini_pname(c, u, m->as.function.params[j].name), "this"))
                mini_perror(c, u, m->name,
                            "this is implicit and cannot be declared as a parameter");
        for (size_t j = 0; j < i; j++)
            if (!strcmp(method.name, mini_pname(c, u, d->as.struct_decl.methods[j]->name)))
                mini_perror(c, u, m->name, "duplicate method");
        Method *old = mini_pmethod(t, method.name);
        if (old) {
            if (!(m->flags & MINI_OVERRIDE))
                mini_perror(c, u, m->name, "overriding method requires override");
            else if (!old->open)
                mini_perror(c, u, m->name, "cannot override final method");
            else if (!signature(old, &method))
                mini_perror(c, u, m->name, "override signature does not match");
            else if ((m->flags & MINI_PRIVATE) && !(old->ref.decl->flags & MINI_PRIVATE))
                mini_perror(c, u, m->name, "override cannot reduce visibility");
            unsigned mask = MINI_PUBLIC | MINI_PRIVATE | MINI_PROTECTED | MINI_INTERNAL;
            if (!(method.flags & mask))
                method.flags |= old->flags & mask;
            unsigned before = old->flags & mask, after = method.flags & mask;
            bool public_before = !before || before == MINI_PUBLIC;
            bool public_after = !after || after == MINI_PUBLIC;
            if ((public_before && !public_after) ||
                (!public_before && !public_after && before != after))
                mini_perror(c, u, m->name, "override cannot reduce or change inherited visibility");
            *old = method;
        } else {
            if (m->flags & MINI_OVERRIDE)
                mini_perror(c, u, m->name, "override has no matching base method");
            mini_pappend(c, (void **)&t->methods, &t->method_count, sizeof(method), &method);
        }
        if (method.abstract && !(d->flags & MINI_ABSTRACT) && !d->as.struct_decl.is_interface)
            mini_perror(c, u, m->name, "abstract member requires an abstract class");
    }
    for (size_t i = 0; i < t->method_count && !c->result.message; i++) {
        Method *m = &t->methods[i];
        if (m->conflict)
            mini_perror(c, u, d->name, "conflicting interface defaults require override of '%s'",
                        m->name);
        if (m->abstract && !(d->flags & MINI_ABSTRACT) && !d->as.struct_decl.is_interface)
            mini_perror(c, u, d->name, "class does not implement '%s'", m->name);
    }
    if (!c->result.message)
        constraints(c, env, t->ref, t->args, t->arg_count);
done:
    c->instantiation_depth--;
    t->state = 2;
    return !c->result.message;
}
Function *mini_pfunction(Context *c, Ref ref, Type *owner, Type **args, size_t count) {
    if (!ref.decl || c->result.message)
        return NULL;
    if (ref.decl->generic_count != count) {
        mini_perror(c, ref.unit, ref.decl->name, "function expects %zu generic arguments, got %zu",
                    ref.decl->generic_count, count);
        return NULL;
    }
    for (size_t i = 0; i < c->function_count; i++) {
        Function *f = c->functions[i];
        if (f->ref.decl != ref.decl || f->owner != owner || f->arg_count != count)
            continue;
        size_t j = 0;
        while (j < count && f->args[j] == args[j])
            j++;
        if (j == count)
            return f;
    }
    if (c->function_count >= 4096) {
        mini_perror(c, ref.unit, ref.decl->name, "function instantiation limit exceeded");
        return NULL;
    }
    Function *f = mini_palloc(c, sizeof(*f));
    if (!f)
        return NULL;
    f->id = c->function_count;
    f->ref = ref;
    f->owner = owner;
    f->args = args;
    f->arg_count = count;
    mini_pappend(c, (void **)&c->functions, &c->function_count, sizeof(f), &f);
    Environment env = mini_penv(c, ref, owner, args, count);
    f->result = ref.decl->as.function.return_type
                    ? mini_presolve(c, env, ref.decl->as.function.return_type)
                    : c->builtins[TY_VOID];
    size_t first = owner && !(ref.decl->flags & MINI_STATIC) ? 1 : 0;
    f->param_count = ref.decl->as.function.param_count + first;
    f->params = mini_palloc(c, f->param_count * sizeof(Type *));
    if (first)
        f->params[0] = pointer(c, owner);
    for (size_t i = 0; i < ref.decl->as.function.param_count; i++) {
        f->params[i + first] = mini_presolve(c, env, ref.decl->as.function.params[i].type);
        mini_pstorage(c, ref.unit, ref.decl->name, f->params[i + first], false);
        if (f->params[i + first] && f->params[i + first]->kind == TY_VOID)
            mini_perror(c, ref.unit, ref.decl->name, "parameter cannot have type void");
        for (size_t j = 0; j < i; j++)
            if (!strcmp(mini_pname(c, ref.unit, ref.decl->as.function.params[i].name),
                        mini_pname(c, ref.unit, ref.decl->as.function.params[j].name)))
                mini_perror(c, ref.unit, ref.decl->name, "duplicate parameter");
    }
    if (ref.decl->as.function.is_extern && count)
        mini_perror(c, ref.unit, ref.decl->name, "extern functions cannot be generic");
    mini_pstorage(c, ref.unit, ref.decl->name, f->result, true);
    constraints(c, env, ref, args, count);
    return c->result.message ? NULL : f;
}
/* Shared with expression lowering; lookup never bypasses import/visibility checks. */
Ref mini_plookup(Context *c, Unit *u, const char *name, Token at) {
    return lookup(c, u, name, at);
}
Type *mini_ppointer(Context *c, Type *element) {
    return pointer(c, element);
}
Type *mini_pnominal(Context *c, Ref ref, Type **args, size_t n) {
    return nominal(c, ref, args, n);
}
bool mini_psubtype(Context *c, Type *child, Type *parent) {
    return subtype(c, child, parent, 0);
}

static bool visit_module(Context *c, size_t index, unsigned char *states) {
    if (states[index] == 2)
        return true;
    if (states[index] == 1) {
        mini_perror(c, NULL, (Token){0}, "cyclic module dependency");
        return false;
    }
    states[index] = 1;
    const MiniModule *m = &c->modules[index];
    for (size_t i = 0; i < m->dependency_count; i++) {
        size_t j = 0;
        while (j < c->module_count && strcmp(c->modules[j].name, m->dependencies[i]))
            j++;
        if (j == c->module_count) {
            mini_perror(c, NULL, (Token){0}, "unknown module dependency '%s'", m->dependencies[i]);
            return false;
        }
        if (!visit_module(c, j, states))
            return false;
    }
    states[index] = 2;
    return true;
}
MiniCompileResult mini_compile_project(const MiniSource *sources, size_t count,
                                       const MiniModule *modules, size_t module_count) {
    Context *c = calloc(1, sizeof(*c));
    if (!c) {
        MiniCompileResult r = {0};
        r.message = malloc(14);
        if (r.message)
            memcpy(r.message, "out of memory", 14);
        return r;
    }
    c->modules = modules;
    c->module_count = module_count;
    if (setjmp(c->allocation_failure))
        goto cleanup;
    if (!sources || !count || (module_count && !modules)) {
        mini_perror(c, NULL, (Token){0}, "no source files or invalid module graph");
        goto cleanup;
    }
    if (count > SIZE_MAX / sizeof(Unit)) {
        mini_perror(c, NULL, (Token){0}, "too many source files");
        goto cleanup;
    }
    c->units = mini_palloc(c, count * sizeof(Unit));
    if (!c->units)
        goto cleanup;
    c->unit_count = count;
    for (size_t i = 0; i < 6; i++)
        c->builtins[i] = new_type(c, (TypeKind)i);
    c->object_unit.source = (MiniSource){"<builtin objects>", "mini.builtin",
                                         "package mini.core\npublic open class Object {}\n"
                                         "public open class AllocatableObject : Object {}"};
    MiniParser objects =
        mini_parser_new(c->object_unit.source.text, strlen(c->object_unit.source.text));
    c->object_unit.ast = mini_parse_program(&objects);
    if (!c->object_unit.ast) {
        mini_perror(c, NULL, (Token){0}, "cannot initialize builtin object classes");
        goto cleanup;
    }
    c->object_unit.package = "mini.core";
    c->object_type = nominal(c, (Ref){&c->object_unit, c->object_unit.ast->items[0]}, NULL, 0);
    c->allocatable_type = nominal(c, (Ref){&c->object_unit, c->object_unit.ast->items[1]}, NULL, 0);
    if (module_count) {
        unsigned char *states = mini_palloc(c, module_count);
        for (size_t i = 0; i < module_count && !c->result.message; i++) {
            if (!modules[i].name || (modules[i].dependency_count && !modules[i].dependencies)) {
                mini_perror(c, NULL, (Token){0}, "invalid module definition");
                break;
            }
            for (size_t j = 0; j < modules[i].dependency_count; j++)
                if (!modules[i].dependencies[j])
                    mini_perror(c, NULL, (Token){0}, "missing module dependency name");
            for (size_t j = 0; j < i; j++)
                if (!strcmp(modules[i].name, modules[j].name))
                    mini_perror(c, NULL, (Token){0}, "duplicate module");
        }
        for (size_t i = 0; i < module_count && !c->result.message; i++)
            visit_module(c, i, states);
    }
    for (size_t i = 0; i < count && !c->result.message; i++) {
        Unit *u = &c->units[i];
        u->source = sources[i];
        u->index = i;
        if (!u->source.text) {
            mini_perror(c, u, (Token){0}, "missing source text");
            break;
        }
        if (module_count) {
            bool exists = false;
            for (size_t j = 0; j < module_count; j++)
                if (!strcmp(module_name(u), modules[j].name))
                    exists = true;
            if (!exists) {
                mini_perror(c, u, (Token){0}, "source belongs to an undeclared module");
                break;
            }
        }
        MiniParser parser = mini_parser_new(u->source.text, strlen(u->source.text));
        u->ast = mini_parse_program(&parser);
        if (!u->ast) {
            mini_perror(c, u, parser.error_token, "%s",
                        parser.error_message ? parser.error_message : "parse failed");
            break;
        }
        if (!module_count)
            u->source.module = "main";
        u->package = mini_pname(c, u, u->ast->package_name);
    }
    /* Validate names before lookup: duplicates are never resolved by file order. */
    for (size_t i = 0; i < count && !c->result.message; i++)
        for (size_t j = 0; j < c->units[i].ast->count && !c->result.message; j++) {
            Unit *u = &c->units[i];
            const MiniDecl *d = u->ast->items[j];
            Ref ref = {u, d};
            if (!legal_visibility(c, ref, false))
                break;
            if (d->kind == MINI_DECL_FUNCTION) {
                mini_perror(c, u, d->name, "functions must be declared inside a class");
                break;
            }
            char *name = mini_pname(c, u, d->name);
            if (!strcmp(name, "Object") || !strcmp(name, "AllocatableObject")) {
                mini_perror(c, u, d->name, "builtin object class name is reserved");
                break;
            }
            for (size_t a = 0; a <= i; a++)
                for (size_t b = 0; b < c->units[a].ast->count; b++) {
                    if (a == i && b >= j)
                        break;
                    if (a != i && ((d->flags | c->units[a].ast->items[b]->flags) & MINI_PRIVATE))
                        continue;
                    if (!strcmp(u->package, c->units[a].package) &&
                        !strcmp(name, mini_pname(c, &c->units[a], c->units[a].ast->items[b]->name)))
                        mini_perror(c, u, d->name, "duplicate qualified declaration '%s'", name);
                }
            for (size_t a = 0; a < d->generic_count; a++)
                for (size_t b = 0; b < a; b++)
                    if (!strcmp(mini_pname(c, u, d->generics[a].name),
                                mini_pname(c, u, d->generics[b].name)))
                        mini_perror(c, u, d->generics[a].name, "duplicate generic parameter");
        }
    if (!c->result.message)
        mini_pvalidate(c);
    for (size_t i = 0; i < count && !c->result.message; i++) {
        Unit *u = &c->units[i];
        for (size_t j = 0; j < u->ast->import_count && !c->result.message; j++) {
            MiniImport *im = &u->ast->imports[j];
            char *path = mini_pname(c, u, im->path);
            if (!im->star)
                lookup(c, u,
                       im->alias.length ? mini_pname(c, u, im->alias)
                                        : (strrchr(path, '.') ? strrchr(path, '.') + 1 : path),
                       im->path);
            else {
                bool exists = false;
                for (size_t k = 0; k < count; k++)
                    if (!strcmp(c->units[k].package, path) && dependency(c, u, &c->units[k]))
                        exists = true;
                if (!exists)
                    mini_perror(c, u, im->path, "unknown or inaccessible imported package");
            }
        }
        for (size_t j = 0; j < u->ast->count && !c->result.message; j++) {
            const MiniDecl *d = u->ast->items[j];
            if (d->generic_count)
                continue;
            Ref ref = {u, d};
            if (d->kind == MINI_DECL_FUNCTION)
                mini_perror(c, u, d->name, "functions must be declared inside a class");
            else {
                Type *t = nominal(c, ref, NULL, 0);
                mini_pensure(c, t);
            }
        }
    }
    if (!c->result.message) {
        char *output = mini_pemit(c);
        if (output && !c->result.message) {
            c->result.c_source = malloc(strlen(output) + 1);
            if (c->result.c_source)
                strcpy(c->result.c_source, output);
            else
                mini_perror(c, NULL, (Token){0}, "out of memory");
        }
    }
cleanup:
    mini_program_free(c->object_unit.ast);
    if (c->warnings.length) {
        c->result.warnings = malloc(c->warnings.length + 1);
        if (c->result.warnings)
            memcpy(c->result.warnings, c->warnings.data, c->warnings.length + 1);
    }
    if (c->units)
        for (size_t i = 0; i < c->unit_count; i++)
            mini_program_free(c->units[i].ast);
    while (c->allocations) {
        Allocation *next = c->allocations->next;
        free(c->allocations);
        c->allocations = next;
    }
    MiniCompileResult result = c->result;
    free(c);
    return result;
}
void mini_compile_result_destroy(MiniCompileResult *r) {
    if (r) {
        free(r->c_source);
        free(r->message);
        free(r->warnings);
        memset(r, 0, sizeof(*r));
    }
}
