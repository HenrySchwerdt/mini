# Mini Language Grammar (v0.1)

A small, statically typed, imperative language designed for straightforward parsing, explicit memory management, LLVM code generation, and eventual self-hosting.

**Status:** Draft specification. This defines the syntax and a minimal set of semantic conventions; it is not yet a complete language specification.

## 1. Example

```mini
struct Token {
    kind: TokenKind,
    value: i32,
}

enum TokenKind {
    Eof,
    Number,
    Plus,
    Minus,
}

fn precedence(kind: TokenKind) -> i32 {
    return match kind {
        TokenKind.Plus => 10,
        TokenKind.Minus => 10,
        _ => 0,
    };
}

fn main() -> i32 {
    let token: Token = Token {
        kind: TokenKind.Number,
        value: 42,
    };

    let raw: *u8 = heap.alloc(sizeof(Token));
    if raw == null {
        return 1;
    }
    let ptr: *Token = cast<*Token>(raw);
    *ptr = token;

    print_i32(precedence((*ptr).kind));
    heap.free(ptr);
    return 0;
}
```

`heap.alloc`, `heap.free`, and `print_i32` are runtime functions made available by the initial toolchain. `null` is a pointer constant; the compiler checks contextual pointer typing.

## 2. Grammar notation

The following is **EBNF-like notation**:

- `"text"` denotes a literal token.
- `A B` denotes a sequence.
- `A | B` denotes a choice.
- `[ A ]` denotes an optional element **in the grammar notation only**.
- `{ A }` denotes zero or more repetitions **in the grammar notation only**.
- Comments in the grammar use `(* ... *)`.
- `IDENT`, `INT`, and `STRING` are tokens provided by the lexer.

## 3. Grammar

```ebnf
program          = { declaration }, EOF ;

declaration      = function_decl | extern_decl | struct_decl | enum_decl ;

function_decl    = "fn", IDENT, "(", [ parameters ], ")",
                   [ "->", type ], block ;

extern_decl      = "extern", "fn", IDENT, "(", [ parameters ], ")",
                   [ "->", type ], ";" ;

parameters       = parameter, { ",", parameter } ;
parameter        = IDENT, ":", type ;

struct_decl      = "struct", IDENT, "{", [ fields ], "}" ;
fields           = field, { ",", field }, [ "," ] ;
field            = IDENT, ":", type ;

enum_decl        = "enum", IDENT, "{", [ variants ], "}" ;
variants         = IDENT, { ",", IDENT }, [ "," ] ;

(* Types *)

type             = "*", type
                 | "[", type, ";", INT, "]"
                 | IDENT ;

(* Statements *)

block            = "{", { statement }, "}" ;
statement        = let_stmt
                 | return_stmt
                 | if_stmt
                 | while_stmt
                 | block
                 | expr_stmt ;

let_stmt         = "let", IDENT, ":", type, [ "=", expression ], ";" ;
return_stmt      = "return", [ expression ], ";" ;
if_stmt          = "if", expression, block, [ "else", ( if_stmt | block ) ] ;
while_stmt       = "while", expression, block ;
expr_stmt        = expression, ";" ;

(* Expressions: lowest precedence to highest *)

expression       = assignment ;
assignment       = logical_or, [ "=", assignment ] ;
logical_or       = logical_and, { "||", logical_and } ;
logical_and      = equality, { "&&", equality } ;
equality         = comparison, { ( "==" | "!=" ), comparison } ;
comparison       = additive, { ( "<" | "<=" | ">" | ">=" ), additive } ;
additive         = multiplicative, { ( "+" | "-" ), multiplicative } ;
multiplicative   = unary, { ( "*" | "/" | "%" ), unary } ;
unary            = ( "!" | "-" | "*" | "&" ), unary | postfix ;
postfix          = primary, { postfix_op } ;
postfix_op       = "(", [ arguments ], ")"
                 | "[", expression, "]"
                 | ".", IDENT ;
arguments        = expression, { ",", expression } ;

primary          = INT
                 | STRING
                 | "true"
                 | "false"
                 | "null"
                 | IDENT
                 | "(", expression, ")"
                 | struct_literal
                 | match_expr
                 | sizeof_expr
                 | cast_expr ;

struct_literal   = IDENT, "{", [ field_values ], "}" ;
field_values     = field_value, { ",", field_value }, [ "," ] ;
field_value      = IDENT, ":", expression ;

match_expr       = "match", expression, "{", [ match_arms ], "}" ;
match_arms       = match_arm, { ",", match_arm }, [ "," ] ;
match_arm        = pattern, "=>", expression ;
pattern          = INT | "true" | "false" | "_"
                 | IDENT, ".", IDENT ;

sizeof_expr      = "sizeof", "(", type, ")" ;
cast_expr        = "cast", "<", type, ">", "(", expression, ")" ;
```

### Important parsing convention

`if condition { ... }`, `while condition { ... }`, and `match subject { ... }` can be ambiguous with `Type { field: value }` struct literals. To keep the syntax familiar and the parser simple:

- In the **outermost condition/subject expression**, an unparenthesized `{` begins the control-flow body, not a struct literal.
- If the condition or subject itself must be a struct literal, wrap it in parentheses, e.g. `match (Token { kind: TokenKind.Eof, value: 0 }) { ... }`.
- Parenthesized expressions and function arguments may contain struct literals normally.

A recursive-descent/Pratt parser can implement this with a contextual `allow_top_level_struct_literal` flag. `cast<...>(...)` and `sizeof(...)` are recognized as built-in syntactic forms, not generic function calls.

## 4. Lexical rules

```ebnf
IDENT            = ( LETTER | "_" ), { LETTER | DIGIT | "_" } ;
INT              = DIGIT, { DIGIT } ;
STRING           = '"', { STRING_CHAR | ESCAPE }, '"' ;
ESCAPE           = "\\", ( "n" | "r" | "t" | "0" | '"' | "\\" ) ;
LETTER           = "a"..."z" | "A"..."Z" ;
DIGIT            = "0"..."9" ;
```

`STRING_CHAR` means any permitted source character except an unescaped `"`, `\`, or newline. Initially, identifiers are ASCII-only and integer literals are decimal-only. Strings represent immutable, statically stored, NUL-terminated UTF-8 byte sequences; their static type is `*u8` in v0.1, with writes to string-literal storage prohibited by the typechecker/runtime contract until a `*const u8` type is introduced.

Whitespace is ignored except as a token separator. Line comments begin with `//` and terminate at the end of the line. Block comments use `/* ... */` and are not nested.

Reserved keywords: `fn`, `extern`, `struct`, `enum`, `let`, `return`, `if`, `else`, `while`, `match`, `true`, `false`, `null`, `sizeof`, `cast`.

## 5. Core types

| Type | Meaning |
| --- | --- |
| `u8` | Unsigned 8-bit integer |
| `i32` | Signed 32-bit integer |
| `usize` | Unsigned pointer-width integer |
| `isize` | Signed pointer-width integer |
| `bool` | Boolean |
| `void` | Function returns no value (return type only) |
| `*T` | Pointer to `T` |
| `[T; N]` | Fixed-size array of `N` elements |
| `struct Name` | Record type with named fields (referenced as `Name`) |
| `enum Name` | Integer-backed enum without payload (referenced as `Name`) |

The grammar uses `IDENT` for both built-in and user-defined type names; type resolution is semantic rather than syntactic.

## 6. Semantic rules (v0.1)

1. Variables declared by `let` are **mutable** and have block scope. Uninitialized values may not be read; the checker must establish definite initialization before reads.
2. Assignment is right-associative. Its left-hand side must be a writable *place expression*: a variable, dereference, field, or array element. For v0.1, assignment is an expression with type `void` and cannot be embedded where a value is required.
3. Operators are statically typechecked. `&&` and `||` short-circuit. Boolean conditions must have type `bool`; numeric-to-boolean conversion is not implicit.
4. `match` evaluates its subject exactly once and selects the first matching arm. Every arm produces the same type. `_` is the fallback pattern. Duplicate/unreachable arms are errors. Matches must be exhaustive: all enum/bool alternatives or a wildcard; integer matches require a wildcard.
5. `enum` variants have no payload and compile to integer tags. `struct` fields are stored in declaration order, with target-ABI alignment and padding.
6. `return;` is allowed only in `void` functions. Omitting `-> T` means `-> void`.
7. Fixed arrays may be declared without an initializer, but their elements are uninitialized until written. Array indexing does not automatically guarantee bounds checking in v0.1; this is a deliberate safety limitation.
8. `sizeof(T)` yields `usize` and includes ABI padding. `cast<T>(value)` supports explicitly defined numeric/pointer conversions only; unsupported casts fail at compile time.
9. `null` is valid only in a context requiring a pointer. Pointer dereference requires a non-null, valid, suitably aligned address. Heap allocation can fail; the program must test for `null` before dereferencing.
10. `extern fn` declares a symbol supplied by the runtime or another linked library. External function signatures must match the target ABI. `heap.alloc(n)` is syntactic sugar for member access and a function call; how names like `heap.alloc` are resolved is a compiler-provided namespace rule in v0.1.

## 7. Minimal runtime contract

The bootstrap compiler links its generated object files against a small runtime library. The user-facing functions can be surfaced under a `heap` namespace and simple I/O names.

| Function | Type | Purpose |
| --- | --- | --- |
| `heap.alloc` | `(usize) -> *u8` | Allocate bytes; returns `null` on failure |
| `heap.realloc` | `(*u8, usize) -> *u8` | Resize allocation; returns `null` on failure, preserving the original allocation |
| `heap.free` | `(*u8) -> void` | Free an allocation |
| `read` | `(i32, *u8, usize) -> isize` | Read bytes from an OS handle (POSIX-oriented v0.1) |
| `write` | `(i32, *u8, usize) -> isize` | Write bytes to an OS handle |
| `print_i32` | `(i32) -> void` | Convenience print for initial examples |

Typed pointers such as `*Token` are **not implicitly interchangeable** with `*u8`. A typed allocation may be written as:

```mini
let raw: *u8 = heap.alloc(sizeof(Token));
if raw == null {
    return 1;
}
let ptr: *Token = cast<*Token>(raw);
```

For portability, the runtime may export `mini_alloc`, `mini_realloc`, `mini_free`, etc. with stable C ABI symbols while the compiler maps language-level names to those symbols. A standalone self-hosted standard library can later replace most convenience wrappers.

## 8. Implementation notes

A small compiler can use:

1. A hand-written lexer producing token kinds and byte spans.
2. Recursive descent for declarations, statements, and types.
3. A Pratt parser for expressions and operator precedence.
4. An AST with arena allocation and source spans.
5. Symbol resolution, a typechecker, and `match` exhaustiveness checks.
6. LLVM IR generation, object generation, and linking to the runtime.

**Self-hosting prerequisite:** This grammar gives the basic building blocks for a compiler, but practical self-hosting also requires reliable source-file I/O, allocation growth, error reporting, and compiler/runtime ABI agreement. Dynamic vectors and strings can be implemented in Mini using pointers, structs, and the runtime functions rather than being built-in language types.

## 9. Explicitly deferred

Generics, closures, methods, inheritance, garbage collection, tagged unions with payloads, destructuring patterns, match guards, modules/imports, operator overloading, concurrency, and a full package manager are **not** part of v0.1.
