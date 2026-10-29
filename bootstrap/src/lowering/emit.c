#include "project_internal.h"
#include <string.h>

char *mini_pctype(Context *c, Type *t) {
    if (!t)
        return "void";
    static const char *names[] = {"void", "int32_t", "uint8_t", "uintptr_t", "intptr_t", "bool"};
    if (t->kind <= TY_BOOL)
        return (char *)names[t->kind];
    return mini_pformat(c, "mini_type_%zu", t->id);
}
static bool layout(Context *c, Buffer *out, Type *t, size_t depth) {
    if (t->kind != TY_NOMINAL && t->kind != TY_ARRAY && t->kind != TY_OPTIONAL &&
        t->kind != TY_RESULT && t->kind != TY_FUNCTION)
        return true;
    if (t->layout_state == 2)
        return true;
    if (t->layout_state == 1 || depth > 128) {
        mini_perror(c, t->ref.unit, t->ref.decl ? t->ref.decl->name : (Token){0},
                    "recursive value layout; use a pointer");
        return false;
    }
    t->layout_state = 1;
    if (t->kind == TY_FUNCTION) {
        mini_plambda_layout(c, out, t);
    } else if (t->kind == TY_OPTIONAL || t->kind == TY_RESULT) {
        for (size_t i = 0; i < t->arg_count; i++)
            if (!layout(c, out, t->args[i], depth + 1))
                return false;
        mini_pwrite(c, out, "struct mini_type_%zu { uint8_t tag; union { %s value;", t->id,
                    mini_pctype(c, t->args[0]));
        if (t->kind == TY_RESULT)
            mini_pwrite(c, out, " %s error;", mini_pctype(c, t->args[1]));
        mini_pwrite(c, out, " } data; };\n");
    } else if (t->kind == TY_ARRAY) {
        if (!layout(c, out, t->element, depth + 1))
            return false;
        mini_pwrite(c, out, "struct mini_type_%zu { %s data[%zu]; };\n", t->id,
                    mini_pctype(c, t->element), t->length);
    } else if (t->ref.decl->kind == MINI_DECL_ENUM) {
        const MiniDecl *d = t->ref.decl;
        for (size_t i = 0; i < d->as.enum_decl.variant_count; i++)
            for (size_t j = 0; j < i; j++)
                if (!strcmp(mini_pname(c, t->ref.unit, d->as.enum_decl.variants[i]),
                            mini_pname(c, t->ref.unit, d->as.enum_decl.variants[j])))
                    mini_perror(c, t->ref.unit, d->as.enum_decl.variants[i],
                                "duplicate enum variant");
    } else {
        if (!mini_pensure(c, t))
            return false;
        for (size_t i = 0; i < t->field_count; i++)
            if (!layout(c, out, t->fields[i].type, depth + 1))
                return false;
        mini_pwrite(c, out, "struct mini_type_%zu {\n", t->id);
        bool tagged =
            t->ref.decl->as.struct_decl.is_class || t->ref.decl->as.struct_decl.is_interface;
        if (tagged)
            mini_pwrite(c, out, "uint32_t mini_tag;\n");
        for (size_t i = 0; i < t->field_count; i++)
            mini_pwrite(c, out, "%s field_%zu;\n", mini_pctype(c, t->fields[i].type), i);
        if (!tagged && !t->field_count)
            mini_pwrite(c, out, "uint8_t mini_empty;\n");
        mini_pwrite(c, out, "};\n");
    }
    t->layout_state = 2;
    return !c->result.message;
}
static void prototype(Context *c, Buffer *out, Function *f, bool names) {
    bool ext = f->ref.decl->as.function.is_extern;
    mini_pwrite(c, out, "%s%s %s(", ext ? "extern " : "static ", mini_pctype(c, f->result),
                ext ? mini_pextern_name(c, f->ref) : mini_pformat(c, "mini_fn_%zu", f->id));
    size_t n = f->param_count;
    if (!n)
        mini_pwrite(c, out, "void");
    for (size_t i = 0; i < n; i++)
        mini_pwrite(c, out, "%s%s%s%s", i ? ", " : "", mini_pctype(c, f->params[i]),
                    names ? " " : "", names ? mini_pformat(c, "param%zu", i) : "");
    mini_pwrite(c, out, ")");
}
static void dispatcher(Context *c, Buffer *out, Type *t, size_t index, bool body) {
    Method *m = &t->methods[index];
    if (!m->open)
        return;
    mini_pwrite(c, out, "static %s mini_dispatch_%zu_%zu(%s* self", mini_pctype(c, m->result),
                t->id, index, mini_pctype(c, t));
    for (size_t i = 1; i < m->param_count; i++)
        mini_pwrite(c, out, ", %s param%zu", mini_pctype(c, m->params[i]), i);
    mini_pwrite(c, out, ")");
    if (!body) {
        mini_pwrite(c, out, ";\n");
        return;
    }
    /* memcpy avoids strict-aliasing violations when reading the shared tag. */
    mini_pwrite(c, out, " { uint32_t tag; memcpy(&tag, self, sizeof(tag)); switch(tag) {\n");
    for (size_t i = 0; i < c->type_count; i++) {
        Type *candidate = c->types[i];
        if (candidate->kind != TY_NOMINAL || candidate->ref.decl->kind != MINI_DECL_STRUCT ||
            !candidate->ref.decl->as.struct_decl.is_class ||
            (candidate->ref.decl->flags & MINI_ABSTRACT))
            continue;
        if (!mini_psubtype(c, candidate, t))
            continue;
        Method *actual = mini_pmethod(candidate, m->name);
        if (!actual || actual->abstract)
            continue;
        Function *fn = mini_pfunction(c, actual->ref, actual->owner, NULL, 0);
        if (!fn)
            return;
        mini_pwrite(c, out, "case %zu: %smini_fn_%zu((%s*)self", candidate->id,
                    m->result->kind == TY_VOID ? "" : "return ", fn->id,
                    mini_pctype(c, actual->owner));
        for (size_t j = 1; j < m->param_count; j++)
            mini_pwrite(c, out, ", param%zu", j);
        mini_pwrite(c, out, "); %s\n", m->result->kind == TY_VOID ? "return;" : "");
    }
    mini_pwrite(c, out, "default: abort(); } }\n");
}
static void emit_body(Context *c, Function *f) {
    if (f->emitted || f->ref.decl->as.function.is_extern)
        return;
    f->emitted = true;
    Body b = {.c = c,
              .function = f,
              .env = mini_penv(c, f->ref, f->owner, f->args, f->arg_count),
              .out = &f->body};
    size_t first = f->owner && !(f->ref.decl->flags & MINI_STATIC) ? 1 : 0;
    for (size_t i = 0; i < f->param_count; i++) {
        Local *v = mini_palloc(c, sizeof(*v));
        v->name = first && i == 0
                      ? "this"
                      : mini_pname(c, f->ref.unit, f->ref.decl->as.function.params[i - first].name);
        v->is_this = first && i == 0;
        v->code = mini_pformat(c, "param%zu", i);
        v->type = f->params[i];
        v->initialized = true;
        v->assigned = true;
        v->immutable = true;
        mini_pappend(c, (void **)&b.locals, &b.local_count, sizeof(v), &v);
    }
    bool returns = mini_pstatement(&b, f->ref.decl->as.function.body);
    if (f->result && f->result->kind != TY_VOID && !returns)
        mini_perror(c, f->ref.unit, f->ref.decl->name, "not all paths return a value");
}
char *mini_pemit(Context *c) {
    /* Work queues may grow as generic bodies instantiate other functions/types. */
    size_t type_index = 0, function_index = 0;
    while (!c->result.message &&
           (type_index < c->type_count || function_index < c->function_count)) {
        while (type_index < c->type_count && !c->result.message) {
            Type *t = c->types[type_index++];
            if (!mini_pensure(c, t))
                break;
            if (t->kind == TY_NOMINAL && t->ref.decl->kind == MINI_DECL_STRUCT)
                for (size_t i = 0; i < t->method_count && !c->result.message; i++)
                    if (!t->methods[i].abstract && !t->methods[i].ref.decl->generic_count)
                        mini_pfunction(c, t->methods[i].ref, t->methods[i].owner, NULL, 0);
        }
        if (function_index < c->function_count && !c->result.message)
            emit_body(c, c->functions[function_index++]);
    }
    if (c->result.message)
        return NULL;
    Buffer out = {0};
    mini_pwrite(
        c, &out,
        "/* Generated by Mini. C17 is the concrete, portable lowering format. */\n"
        "#ifndef _WIN32\n#ifndef _POSIX_C_SOURCE\n#define _POSIX_C_SOURCE 200809L\n#endif\n#endif\n"
        "#include <stdint.h>\n#include <stddef.h>\n#include <stdbool.h>\n#include "
        "<stdlib.h>\n#include <stdio.h>\n#include <string.h>\n#include "
        "<inttypes.h>\n#include <limits.h>\n"
        "#ifdef _WIN32\n#include <io.h>\n#else\n#include <unistd.h>\n#endif\n"
        "static void* mini_rt_checked_pointer(void* p) { if (!p) abort(); return p; }\n"
        "static uint8_t* mini_rt_alloc(uintptr_t n) { return n ? (uint8_t*)malloc(n) : NULL; }\n"
        "static uint8_t* mini_rt_realloc(uint8_t* p, uintptr_t n) { return "
        "n ? (uint8_t*)realloc(p,n) : NULL; }\n"
        "static void mini_rt_free(uint8_t* p) { free(p); }\n"
        "static void mini_rt_print_i32(int32_t n) { printf(\"%%\" PRId32 \"\\n\",n); }\n"
        "static intptr_t mini_rt_read(int32_t fd,uint8_t* p,uintptr_t n) {\n"
        "#ifdef _WIN32\nreturn _read(fd,p,(unsigned "
        "int)(n>INT_MAX?INT_MAX:n));\n#else\nreturn read(fd,p,n);\n#endif\n}\n"
        "static intptr_t mini_rt_write(int32_t fd,uint8_t* p,uintptr_t n) {\n"
        "#ifdef _WIN32\nreturn _write(fd,p,(unsigned "
        "int)(n>INT_MAX?INT_MAX:n));\n#else\nreturn write(fd,p,n);\n#endif\n}\n");
    mini_puv_runtime(c, &out);
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        if (t->kind == TY_OPTIONAL || t->kind == TY_RESULT || t->kind == TY_ARRAY ||
            t->kind == TY_FUNCTION ||
            (t->kind == TY_NOMINAL && t->ref.decl->kind != MINI_DECL_ENUM))
            mini_pwrite(c, &out, "typedef struct mini_type_%zu mini_type_%zu;\n", t->id, t->id);
        else if (t->kind == TY_NOMINAL)
            mini_pwrite(c, &out, "typedef int32_t mini_type_%zu;\n", t->id);
    }
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        if (t->kind == TY_POINTER)
            mini_pwrite(c, &out, "typedef %s* mini_type_%zu;\n", mini_pctype(c, t->element), t->id);
    }
    for (size_t i = 0; i < c->type_count && !c->result.message; i++)
        layout(c, &out, c->types[i], 0);
    for (size_t i = 0; i < c->function_count; i++) {
        prototype(c, &out, c->functions[i], false);
        mini_pwrite(c, &out, ";\n");
    }
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        for (size_t j = 0; j < t->method_count; j++)
            dispatcher(c, &out, t, j, false);
    }
    mini_plambda_emit(c, &out, true);
    mini_puv_bridge(c, &out);
    for (size_t i = 0; i < c->function_count; i++) {
        Function *f = c->functions[i];
        if (f->ref.decl->as.function.is_extern)
            continue;
        prototype(c, &out, f, true);
        mini_pwrite(c, &out, " {\n%s\n}\n", f->body.data ? f->body.data : "");
    }
    for (size_t i = 0; i < c->type_count; i++) {
        Type *t = c->types[i];
        for (size_t j = 0; j < t->method_count; j++)
            dispatcher(c, &out, t, j, true);
    }
    mini_plambda_emit(c, &out, false);
    Function *entry = NULL;
    for (size_t i = 0; i < c->function_count; i++) {
        Function *f = c->functions[i];
        if (!f->owner || f->owner->arg_count || !(f->ref.decl->flags & MINI_STATIC) ||
            f->arg_count ||
            strcmp(mini_pname(c, f->owner->ref.unit, f->owner->ref.decl->name), "Main") ||
            strcmp(mini_pname(c, f->ref.unit, f->ref.decl->name), "main"))
            continue;
        const char *module = f->ref.unit->source.module ? f->ref.unit->source.module : "main";
        const char *root = c->module_count ? c->modules[0].name : "main";
        if (strcmp(module, root))
            continue;
        if (entry) {
            mini_perror(c, f->ref.unit, f->ref.decl->name, "multiple main functions");
            break;
        }
        entry = f;
    }
    if (entry) {
        c->result.has_entry = true;
        if (entry->ref.decl->flags & (MINI_PRIVATE | MINI_PROTECTED))
            mini_perror(c, entry->ref.unit, entry->ref.decl->name,
                        "entry main must be public or internal");
        if (entry->ref.decl->as.function.param_count || entry->ref.decl->as.function.is_extern ||
            (entry->result->kind != TY_I32 && entry->result->kind != TY_VOID))
            mini_perror(c, entry->ref.unit, entry->ref.decl->name,
                        "main must have no parameters and return i32 or void");
        else
            mini_pwrite(c, &out, "int main(void) { %smini_fn_%zu(); %s}\n",
                        entry->result->kind == TY_I32 ? "return (int)" : "", entry->id,
                        entry->result->kind == TY_VOID ? "return 0;" : "");
    }
    return c->result.message ? NULL : out.data;
}
