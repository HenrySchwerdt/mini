package mini.tool.native

/** The entry point of the `mini` executable. (Not part of `mini test` builds, which bring their own `main`.) */
fun main(args: Array<String>) {
    hostExit(mini.tool.Mini.run(args.toList(), NativePlatform()))
}
