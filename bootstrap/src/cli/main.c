#include "mini/project.h"
#include "mini/compiler.h"
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

static void help(void) {
    puts("Mini compiler v0.2.0\n"
         "Usage: mini0 <check|build|emit-c|emit-llvm> [options] file.mini ...\n"
         "  -o PATH           Output file (default: a.out / a.exe)\n"
         "  --module NAME     Module for positional source files (default: main)\n"
         "  --dep NAME=FILE   Add a dependency module source; repeat for more files\n"
         "  --depends A=B     Declare an additional module dependency A -> B\n"
         "  --cc PATH         C compiler executable (default: MINI_CC or clang)\n"
         "  --libuv-root PATH Override the configured libuv prefix (or MINI_LIBUV_ROOT)\n"
         "check performs parsing, module resolution, type and override checks.\n"
         "build lowers concrete types to C17 and compiles through Clang/LLVM.\n"
         "emit-llvm requires a Clang-compatible compiler. Memory management is explicit.");
}
static char *copy(const char *s) {
    char *v = malloc(strlen(s) + 1);
    if (v)
        strcpy(v, s);
    return v;
}
static char *read_source(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror(path);
        return NULL;
    }
    size_t size = 0, capacity = 4096;
    char *text = malloc(capacity);
    if (!text) {
        fclose(f);
        return NULL;
    }
    for (;;) {
        if (size + 1 == capacity) {
            if (capacity > SIZE_MAX / 2) {
                free(text);
                fclose(f);
                return NULL;
            }
            capacity *= 2;
            char *next = realloc(text, capacity);
            if (!next) {
                free(text);
                fclose(f);
                return NULL;
            }
            text = next;
        }
        size_t n = fread(text + size, 1, capacity - size - 1, f);
        size += n;
        if (ferror(f)) {
            perror(path);
            free(text);
            text = NULL;
            break;
        }
        if (!n || feof(f))
            break;
    }
    fclose(f);
    if (text)
        text[size] = 0;
    return text;
}
static size_t module_index(MiniModule *modules, size_t *count, const char *name) {
    for (size_t i = 0; i < *count; i++)
        if (!strcmp(modules[i].name, name))
            return i;
    modules[*count] = (MiniModule){.name = name};
    return (*count)++;
}
static int add_dependency(MiniModule *module, const char *to, size_t limit) {
    for (size_t i = 0; i < module->dependency_count; i++)
        if (!strcmp(module->dependencies[i], to))
            return 0;
    if (!module->dependencies) {
        module->dependencies = calloc(limit, sizeof(char *));
        if (!module->dependencies)
            return 1;
    }
    ((const char **)module->dependencies)[module->dependency_count++] = to;
    return 0;
}
int main(int argc, char **argv) {
    if (argc < 2 || !strcmp(argv[1], "--help") || !strcmp(argv[1], "help")) {
        help();
        return argc < 2 ? 1 : 0;
    }
    if (!strcmp(argv[1], "--version")) {
        puts("mini0 0.2.0");
        return 0;
    }
    bool check = !strcmp(argv[1], "check");
    MiniBuildMode mode = MINI_BUILD_NATIVE;
    if (!strcmp(argv[1], "emit-c"))
        mode = MINI_EMIT_C;
    else if (!strcmp(argv[1], "emit-llvm"))
        mode = MINI_EMIT_LLVM;
    else if (!check && strcmp(argv[1], "build")) {
        fprintf(stderr, "unknown command: %s\n", argv[1]);
        return 1;
    }
    const char *root = "main", *compiler = getenv("MINI_CC"), *output = NULL;
    const char *libuv_root = getenv("MINI_LIBUV_ROOT");
    MiniSource *sources = calloc((size_t)argc, sizeof(*sources));
    MiniModule *modules = calloc((size_t)argc * 2, sizeof(*modules));
    char **owned = calloc((size_t)argc, sizeof(*owned));
    size_t owned_count = 0, count = 0, module_count = 0;
    int status = 1;
    if (!sources || !modules || !owned)
        goto cleanup;
    for (int i = 2; i < argc; i++) {
        if (!strcmp(argv[i], "--module") || !strcmp(argv[i], "--cc") ||
            !strcmp(argv[i], "--libuv-root") || !strcmp(argv[i], "-o")) {
            if (++i >= argc) {
                fputs("missing option value\n", stderr);
                goto cleanup;
            }
            if (!strcmp(argv[i - 1], "--module"))
                root = argv[i];
            else if (!strcmp(argv[i - 1], "--cc"))
                compiler = argv[i];
            else if (!strcmp(argv[i - 1], "--libuv-root"))
                libuv_root = argv[i];
            else
                output = argv[i];
        }
    }
    size_t root_index = module_index(modules, &module_count, root);
    for (int i = 2; i < argc; i++) {
        const char *arg = argv[i];
        if (!strcmp(arg, "--module") || !strcmp(arg, "--cc") || !strcmp(arg, "--libuv-root") ||
            !strcmp(arg, "-o")) {
            i++;
            continue;
        }
        if (!strcmp(arg, "--dep") || !strcmp(arg, "--depends")) {
            bool dep = !strcmp(arg, "--dep");
            if (++i >= argc) {
                fputs("missing dependency value\n", stderr);
                goto cleanup;
            }
            char *item = copy(argv[i]);
            if (!item)
                goto cleanup;
            owned[owned_count++] = item;
            char *equals = strchr(item, '=');
            if (!equals || equals == item || !equals[1]) {
                fputs("dependency syntax is NAME=FILE or FROM=TO\n", stderr);
                goto cleanup;
            }
            *equals++ = 0;
            if (dep) {
                module_index(modules, &module_count, item);
                if (add_dependency(&modules[root_index], item, (size_t)argc))
                    goto cleanup;
                sources[count++] = (MiniSource){equals, item, NULL};
            } else {
                size_t from = module_index(modules, &module_count, item);
                module_index(modules, &module_count, equals);
                if (add_dependency(&modules[from], equals, (size_t)argc))
                    goto cleanup;
            }
        } else if (arg[0] == '-') {
            fprintf(stderr, "unknown option: %s\n", arg);
            goto cleanup;
        } else
            sources[count++] = (MiniSource){arg, root, NULL};
    }
    if (!count) {
        fputs("no input files\n", stderr);
        goto cleanup;
    }
    for (size_t i = 0; i < count; i++) {
        sources[i].text = read_source(sources[i].path);
        if (!sources[i].text)
            goto cleanup;
    }
    MiniCompileResult result = mini_compile_project(sources, count, modules, module_count);
    if (!result.c_source) {
        size_t index = result.source_index < count ? result.source_index : 0;
        fprintf(stderr, "%s:%zu:%zu: error: %s\n", sources[index].path, result.line, result.column,
                result.message ? result.message : "compilation failed");
        mini_compile_result_destroy(&result);
        goto cleanup;
    }
    if (result.warnings)
        fputs(result.warnings, stderr);
    if (!check && mode == MINI_BUILD_NATIVE && !result.has_entry) {
        fputs("native executable requires class Main with static main()\n", stderr);
        mini_compile_result_destroy(&result);
        goto cleanup;
    }
    if (check) {
        printf("checked %zu source file(s)\n", count);
        status = 0;
    } else {
        if (!output) {
            if (mode == MINI_EMIT_C)
                output = "out.c";
            else if (mode == MINI_EMIT_LLVM)
                output = "out.ll";
#ifdef _WIN32
            else
                output = "a.exe";
#else
            else
                output = "a.out";
#endif
        }
        for (size_t i = 0; i < count; i++)
            if (!strcmp(output, sources[i].path)) {
                fputs("output must not overwrite an input source\n", stderr);
                mini_compile_result_destroy(&result);
                goto cleanup;
            }
        MiniBuildOptions build_options = {.requires_libuv = result.requires_libuv,
                                          .libuv_root = libuv_root};
        status =
            mini_build_output_with_options(result.c_source, output, compiler, mode, &build_options);
        if (!status)
            printf("wrote %s\n", output);
    }
    mini_compile_result_destroy(&result);
cleanup:
    if (sources)
        for (size_t i = 0; i < count; i++)
            free((char *)sources[i].text);
    if (modules)
        for (size_t i = 0; i < module_count; i++)
            free((void *)modules[i].dependencies);
    for (size_t i = 0; i < owned_count; i++)
        free(owned[i]);
    free(sources);
    free(modules);
    free(owned);
    return status;
}
