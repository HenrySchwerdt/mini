# Basics

<<< @/samples/tour/basics.kt{kotlin}

Output:

<<< @/samples/tour/basics.out{text}

## Notes

- Integer arithmetic has Kotlin semantics: `Int` and `Long` overflow wraps, shift counts are masked, `/` and `%` by zero throw `ArithmeticException`, and `Double` to `Int` saturates.
- `for (i in a..b)`, `until`, `downTo` and `step` over `Int`, `Long` and `Char` compile to plain C loops with exact Kotlin semantics (no overflow at `Int.MAX_VALUE`). A range stored in a variable is a real `IntRange` object.
- Evaluation order is left to right even when an operand needs hoisted statements.
