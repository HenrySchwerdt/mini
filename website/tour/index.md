# Language tour

::: tip New here?
[Learn Mini](/learn/) is the step-by-step tutorial with exercises. This tour is the reference listing of the same features.
:::

Mini is Kotlin, so this tour is short on explanation and long on programs. Every listing here is a real file under `website/samples/tour/`, and **every one has a Run button**: it compiles and runs in your browser. CI builds each one with `./minic build`, runs it and compares stdout with the output shown beneath it.

Build and run any of them yourself:

```sh
./minic build website/samples/tour/basics.kt -o build/basics && ./build/basics
```

| Page | Features |
|---|---|
| [Basics](./basics) | functions, default and named arguments, `when`, ranges, loops, labels, templates |
| [Classes and interfaces](./classes) | constructors, properties, inheritance, interfaces, `companion object`, `object` |
| [Data, enum and sealed](./types) | `data class`, `enum class`, sealed hierarchies, exhaustive `when` |
| [Generics](./generics) | generic classes and functions, bounds, `reified` |
| [Lambdas and inline](./lambdas) | closures, function references, `inline`, scope functions |
| [Exceptions](./exceptions) | `try`/`catch`/`finally`, custom exceptions, runtime failures |
| [Extensions and null safety](./extensions) | extension functions and properties, `?.`, `?:`, smart casts, `lazy` |
| [Collections and strings](./collections) | the stdlib: lists, maps, sets, `StringBuilder` |

For what does not work, see [Mini and Kotlin/JVM](/guide/mini-vs-kotlin#known-gaps). For the memory features that Kotlin does not have, continue with the [memory guide](/memory/).
