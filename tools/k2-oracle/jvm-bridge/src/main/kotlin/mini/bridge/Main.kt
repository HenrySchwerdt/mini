package mini.bridge

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList()))
}
