#ifndef MINI_PROJECT_H
#define MINI_PROJECT_H
#include <stddef.h>
#include <stdbool.h>

typedef struct {
    const char *path;
    const char *module;
    const char *text;
} MiniSource;

typedef struct {
    const char *name;
    const char *const *dependencies;
    size_t dependency_count;
} MiniModule;

typedef struct {
    char *c_source;
    char *message;
    char *warnings;
    bool has_entry;
    bool requires_libuv;
    size_t source_index;
    size_t line;
    size_t column;
} MiniCompileResult;

/* Borrows inputs during the call. Result owns its strings; destroy after use.
 * All files of a module are resolved together, independent of input order.
 * A NULL module graph places all sources in one module ("main" by default).
 */
MiniCompileResult mini_compile_project(const MiniSource *sources, size_t count,
                                       const MiniModule *modules, size_t module_count);
void mini_compile_result_destroy(MiniCompileResult *result);
#endif
