#ifndef MINI_COMPILER_H
#define MINI_COMPILER_H
#include "mini/project.h"

typedef enum { MINI_BUILD_NATIVE, MINI_EMIT_C, MINI_EMIT_LLVM } MiniBuildMode;
typedef struct {
    bool requires_libuv;
    const char *libuv_root;
} MiniBuildOptions;

/* Compile a successful project's C17 intermediate using a native C compiler.
 * compiler is an executable path/name, never a shell command. */
int mini_build_output(const char *c_source, const char *output, const char *compiler,
                      MiniBuildMode mode);
int mini_build_output_with_options(const char *c_source, const char *output, const char *compiler,
                                   MiniBuildMode mode, const MiniBuildOptions *options);
#endif
