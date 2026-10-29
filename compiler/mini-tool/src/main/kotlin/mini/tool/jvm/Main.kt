package mini.tool.jvm

import mini.tool.Mini
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Mini.run(args.toList(), JvmPlatform()))
}
