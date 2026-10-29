#ifndef MINI_PROJECT_INTERNAL_H
#define MINI_PROJECT_INTERNAL_H
#include "mini/project.h"
#include "mini/parser.h"
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <setjmp.h>

typedef struct Allocation Allocation;
typedef struct Context Context;
typedef struct Type Type;
typedef struct Function Function;
typedef struct Unit Unit;
typedef struct Lambda Lambda;
typedef struct Body Body;
typedef struct {
    char *data;
    size_t length, capacity;
} Buffer;
typedef struct {
    Unit *unit;
    const MiniDecl *decl;
} Ref;
typedef enum {
    TY_VOID,
    TY_I32,
    TY_U8,
    TY_USIZE,
    TY_ISIZE,
    TY_BOOL,
    TY_POINTER,
    TY_ARRAY,
    TY_NOMINAL,
    TY_OPTIONAL,
    TY_RESULT,
    TY_FUNCTION
} TypeKind;
typedef struct {
    char *name;
    Type *type;
    unsigned flags;
    Type *owner;
} Field;
typedef struct {
    char *name;
    Ref ref;
    Type *owner;
    Type **params;
    size_t param_count;
    Type *result;
    bool open, abstract, conflict;
    unsigned flags;
} Method;
struct Type {
    TypeKind kind;
    size_t id;
    Type *element;
    size_t length;
    Ref ref;
    Type **args;
    size_t arg_count;
    Field *fields;
    size_t field_count;
    Type **supers;
    size_t super_count;
    Method *methods;
    size_t method_count;
    Type *base;
    int state;
    int layout_state;
    int heap_state;
};
struct Unit {
    MiniSource source;
    MiniProgram *ast;
    char *package;
    size_t index;
};
struct Function {
    size_t id;
    Ref ref;
    Type *owner;
    Type **args;
    size_t arg_count;
    Type **params;
    size_t param_count;
    Type *result;
    Buffer body;
    bool emitted;
};
typedef struct {
    Token name;
    Type *value;
    Unit *unit;
} Binding;
typedef struct {
    Binding *items;
    size_t count;
    Type *self;
    Unit *unit;
} Environment;
typedef struct {
    char *name;
    char *code;
    Type *type;
    bool initialized;
    bool assigned;
    bool immutable;
    size_t loop_depth;
    bool readonly;
    bool is_this;
    size_t depth;
} Local;
typedef struct {
    Type *type;
    char *code;
    bool place;
    bool literal;
    bool readonly;
    uint64_t integer;
    Local *local;
    bool polymorphic_view;
    bool immutable_place;
} Value;
struct Body {
    Context *c;
    Function *function;
    Environment env;
    Buffer *out;
    Local **locals;
    size_t local_count;
    size_t depth;
    size_t temporary;
    size_t loop_depth;
    bool writing;
    Type *expected;
    Body *outer;
    Lambda *lambda;
};
typedef struct {
    Local *source;
    Local *local;
} Capture;
struct Lambda {
    size_t id;
    Type *type;
    Capture *captures;
    size_t capture_count;
    Buffer body;
};
struct Context {
    jmp_buf allocation_failure;
    Allocation *allocations;
    MiniCompileResult result;
    Unit *units;
    size_t unit_count;
    const MiniModule *modules;
    size_t module_count;
    Type **types;
    size_t type_count;
    Function **functions;
    size_t function_count;
    Type *builtins[6];
    Unit object_unit;
    Type *object_type;
    Type *allocatable_type;
    Type *uv_task_type;
    size_t instantiation_depth;
    Buffer preamble;
    Buffer warnings;
    Lambda **lambdas;
    size_t lambda_count;
};
typedef struct {
    bool initialized, assigned, readonly;
} LocalState;
LocalState *mini_psnapshot(Body *body, size_t count);
void mini_prestore(Body *body, const LocalState *values, size_t count);
void *mini_palloc(Context *c, size_t size);
char *mini_pformat(Context *c, const char *format, ...);
void mini_pappend(Context *c, void **items, size_t *count, size_t size, const void *value);
void mini_pwrite(Context *c, Buffer *buffer, const char *format, ...);
void mini_perror(Context *c, Unit *u, Token token, const char *format, ...);
char *mini_pname(Context *c, Unit *u, Token token);
Type *mini_presolve(Context *c, Environment env, const MiniType *ast);
Environment mini_penv(Context *c, Ref ref, Type *owner, Type **args, size_t count);
bool mini_pensure(Context *c, Type *type);
bool mini_passignable(Context *c, Type *expected, Type *actual);
Method *mini_pmethod(Type *type, const char *name);
Function *mini_pfunction(Context *c, Ref ref, Type *owner, Type **args, size_t count);
Value mini_pexpr(Body *b, const MiniExpr *expr);
Value mini_pexpected(Body *b, const MiniExpr *expr, Type *expected);
Value mini_pnative(Body *b, const MiniExpr *expr, bool *recognized);
Value mini_pmatch(Body *b, const MiniExpr *expr);
Type *mini_pvariant(Context *c, TypeKind kind, Type **args, size_t count);
bool mini_pstatement(Body *b, const MiniStmt *stmt);
char *mini_pctype(Context *c, Type *type);
bool mini_pstorage(Context *c, Unit *unit, Token at, Type *type, bool allow_void);
bool mini_pvalidate(Context *c);
char *mini_pemit(Context *c);
bool mini_pannotations(Context *c, Ref location, const MiniAnnotation *annotations, size_t count);
char *mini_pextern_name(Context *c, Ref ref);
void mini_pdeprecated(Context *c, Unit *unit, Token at, Ref ref);
Ref mini_plookup(Context *c, Unit *u, const char *name, Token at);
Type *mini_ppointer(Context *c, Type *element);
Type *mini_pnominal(Context *c, Ref ref, Type **args, size_t n);
bool mini_psubtype(Context *c, Type *child, Type *parent);
bool mini_pheapable(Context *c, Unit *unit, Token at, Type *type);
Value mini_pobject_call(Body *b, const MiniExpr *expr, Type *owner, const char *name,
                        bool *recognized);
Type *mini_pfunctype(Context *c, Type **params, size_t count, Type *result);
Local *mini_plocal(Body *body, const char *name);
Value mini_plambda(Body *body, const MiniExpr *expr);
Value mini_pinvoke(Body *body, const MiniExpr *expr, Value callee);
void mini_plambda_layout(Context *c, Buffer *out, Type *type);
void mini_plambda_emit(Context *c, Buffer *out, bool prototypes);
void mini_puv_runtime(Context *c, Buffer *out);
void mini_puv_bridge(Context *c, Buffer *out);
#endif
