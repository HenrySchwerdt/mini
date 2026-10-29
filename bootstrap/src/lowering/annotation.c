#include "project_internal.h"
#include <string.h>

static char *string_argument(Context *c, Unit *u, const MiniAnnotation *a) {
    if (a->arg_count != 1 || a->args[0]->kind != MINI_EXPR_STRING)
        return NULL;
    Token t = a->args[0]->as.literal;
    char *s = mini_palloc(c, t.length - 1);
    if (s)
        memcpy(s, u->source.text + t.start + 1, t.length - 2);
    return s;
}
bool mini_pannotations(Context *c, Ref ref, const MiniAnnotation *annotations, size_t count) {
    for (size_t i = 0; i < count && !c->result.message; i++) {
        const MiniAnnotation *a = &annotations[i];
        char *name = mini_pname(c, ref.unit, a->name);
        if (!strcmp(name, "Deprecated") || !strcmp(name, "Extern")) {
            char *text = string_argument(c, ref.unit, a);
            if (!text) {
                mini_perror(c, ref.unit, a->name, "@%s requires one string literal", name);
                break;
            }
            if (!strcmp(name, "Extern")) {
                if (ref.decl->kind != MINI_DECL_FUNCTION || !ref.decl->as.function.is_extern ||
                    !(ref.decl->flags & MINI_STATIC)) {
                    mini_perror(c, ref.unit, a->name, "@Extern requires a static extern method");
                    break;
                }
                for (size_t j = 0; text[j]; j++)
                    if (!((text[j] >= 'a' && text[j] <= 'z') ||
                          (text[j] >= 'A' && text[j] <= 'Z') || text[j] == '_' ||
                          (j && text[j] >= '0' && text[j] <= '9'))) {
                        mini_perror(c, ref.unit, a->name, "external symbol must be a C identifier");
                        break;
                    }
                if (!*text)
                    mini_perror(c, ref.unit, a->name, "external symbol is empty");
            }
            continue;
        }
        Ref declaration = mini_plookup(c, ref.unit, name, a->name);
        if (!declaration.decl)
            break;
        if (declaration.decl->kind != MINI_DECL_STRUCT ||
            !declaration.decl->as.struct_decl.is_annotation) {
            mini_perror(c, ref.unit, a->name,
                        "annotation name must resolve to an annotation class");
            break;
        }
        size_t expected = declaration.decl->as.struct_decl.field_count;
        if (expected != a->arg_count) {
            mini_perror(c, ref.unit, a->name, "incorrect annotation argument count");
            break;
        }
        Environment env = mini_penv(c, declaration, NULL, NULL, 0);
        for (size_t j = 0; j < expected && !c->result.message; j++) {
            Type *t = mini_presolve(c, env, declaration.decl->as.struct_decl.fields[j].type);
            MiniExpr *value = a->args[j];
            bool valid =
                t &&
                ((value->kind == MINI_EXPR_STRING && t->kind == TY_POINTER &&
                  t->element == c->builtins[TY_U8]) ||
                 (value->kind == MINI_EXPR_INTEGER && t->kind >= TY_I32 && t->kind <= TY_ISIZE) ||
                 (value->kind == MINI_EXPR_BOOL && t->kind == TY_BOOL));
            if (!valid)
                mini_perror(c, ref.unit, a->name,
                            "annotation arguments must be matching primitive literals");
        }
    }
    return !c->result.message;
}
char *mini_pextern_name(Context *c, Ref ref) {
    for (size_t i = 0; i < ref.decl->annotation_count; i++)
        if (!strcmp(mini_pname(c, ref.unit, ref.decl->annotations[i].name), "Extern")) {
            char *name = string_argument(c, ref.unit, &ref.decl->annotations[i]);
            if (name)
                return name;
        }
    return mini_pname(c, ref.unit, ref.decl->name);
}
void mini_pdeprecated(Context *c, Unit *u, Token at, Ref ref) {
    for (size_t i = 0; i < ref.decl->annotation_count; i++)
        if (!strcmp(mini_pname(c, ref.unit, ref.decl->annotations[i].name), "Deprecated")) {
            char *message = string_argument(c, ref.unit, &ref.decl->annotations[i]);
            if (!message)
                return;
            size_t line = 1, column = 1;
            for (size_t j = 0; j < at.start && u->source.text[j]; j++) {
                if (u->source.text[j] == '\n') {
                    line++;
                    column = 1;
                } else
                    column++;
            }
            char *warning = mini_pformat(c, "%s:%zu:%zu: warning: %s is deprecated: %s\n",
                                         u->source.path ? u->source.path : "<source>", line, column,
                                         mini_pname(c, ref.unit, ref.decl->name), message);
            if (!c->warnings.data || !strstr(c->warnings.data, warning))
                mini_pwrite(c, &c->warnings, "%s", warning);
        }
}
