# Lambdas and inline

<<< @/samples/tour/lambdas.kt{kotlin}

Output:

<<< @/samples/tour/lambdas.out{text}

## Notes

- A lambda that is not inlined becomes a small object (a closure) with one field per captured value. A captured `var` such as `n` in `makeCounter` lives in a shared heap cell that the function and all closures use.
- A call of an `inline` function is expanded in place together with the lambda passed to it. No closure object is created, captured variables are used directly, and `return` inside the lambda returns from the enclosing function (`firstBig`).
- Limits: an inline lambda parameter may only be invoked or passed to another inline function. Store it by declaring the parameter `noinline`.
- Scope functions (`let`, `also`, `apply`, `with`, `run`) are ordinary stdlib source and are inlined.

See [Closures and inline](/internals/closures).
