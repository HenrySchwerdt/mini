#ifndef MINI_SYMBOL_H
#define MINI_SYMBOL_H

#include <stdbool.h>
#include <stddef.h>

#include "mini/ast.h"
#include "mini/type.h"

typedef struct MiniScope MiniScope;

typedef enum {
    MINI_SYMBOL_VARIABLE,
    MINI_SYMBOL_PARAMETER,
    MINI_SYMBOL_FUNCTION,
    MINI_SYMBOL_TYPE
} MiniSymbolKind;

typedef struct {
    const char *data;
    size_t length;
} MiniSymbolName;

typedef struct {
    MiniSymbolKind kind;
    MiniSymbolName name;

    const MiniResolvedType *type;

    /*
     * Non-owning reference to an AST declaration.
     * NULL for local variables and parameters.
     */
    const MiniDecl *declaration;
} MiniSymbol;

/*
 * Create a lexical scope.
 *
 * Parent is borrowed.
 * Destroying a scope does not destroy its parent.
 */
MiniScope *mini_scope_create(MiniScope *parent);

void mini_scope_destroy(MiniScope *scope);

/*
 * Insert a symbol into the current scope.
 *
 * Returns false if the name already exists
 * locally or allocation fails.
 *
 * The scope stores a copy of the symbol,
 * but borrows name.data and declaration.
 */
bool mini_scope_define(
    MiniScope *scope,
    MiniSymbol symbol
);

/*
 * Look up a symbol in the current scope only.
 *
 * Returns a borrowed pointer.
 */
const MiniSymbol *mini_scope_lookup_local(
    const MiniScope *scope,
    MiniSymbolName name
);

/*
 * Look up a symbol in this scope and parents.
 */
const MiniSymbol *mini_scope_lookup(
    const MiniScope *scope,
    MiniSymbolName name
);

#endif
