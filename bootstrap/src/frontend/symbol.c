#include "mini/symbol.h"

#include <stdlib.h>
#include <string.h>
#include <stdint.h>

struct MiniScope {
    MiniScope *parent;

    MiniSymbol *symbols;
    size_t count;
    size_t capacity;
};

static bool name_equal(
    MiniSymbolName a,
    MiniSymbolName b
) {
    return a.length == b.length &&
           (a.length == 0 ||
            memcmp(a.data, b.data, a.length) == 0);
}

MiniScope *mini_scope_create(MiniScope *parent) {
    MiniScope *scope = calloc(1, sizeof(*scope));

    if (!scope)
        return NULL;

    scope->parent = parent;
    return scope;
}

void mini_scope_destroy(MiniScope *scope) {
    if (!scope)
        return;

    free(scope->symbols);
    free(scope);
}

const MiniSymbol *mini_scope_lookup_local(
    const MiniScope *scope,
    MiniSymbolName name
) {
    if (!scope)
        return NULL;

    for (size_t i = scope->count; i > 0; i--) {
        const MiniSymbol *symbol = &scope->symbols[i - 1];

        if (name_equal(symbol->name, name))
            return symbol;
    }

    return NULL;
}

const MiniSymbol *mini_scope_lookup(
    const MiniScope *scope,
    MiniSymbolName name
) {
    while (scope) {
        const MiniSymbol *symbol =
            mini_scope_lookup_local(scope, name);

        if (symbol)
            return symbol;

        scope = scope->parent;
    }

    return NULL;
}

bool mini_scope_define(
    MiniScope *scope,
    MiniSymbol symbol
) {
    if (!scope || !symbol.name.data ||
        symbol.name.length == 0)
        return false;

    if (mini_scope_lookup_local(scope, symbol.name))
        return false;

    if (scope->count == scope->capacity) {
        size_t next_capacity =
            scope->capacity == 0 ? 8 : scope->capacity * 2;

        if (next_capacity < scope->capacity ||
            next_capacity > SIZE_MAX / sizeof(*scope->symbols))
            return false;

        MiniSymbol *next = realloc(
            scope->symbols,
            next_capacity * sizeof(*next)
        );

        if (!next)
            return false;

        scope->symbols = next;
        scope->capacity = next_capacity;
    }

    scope->symbols[scope->count++] = symbol;
    return true;
}
