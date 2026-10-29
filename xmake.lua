set_project("mini")
set_version("0.3.0")
set_languages("c17")

add_rules("mode.debug", "mode.release")
add_rules("plugin.compile_commands.autoupdate", {outputdir = ".", lsp = "clangd"})

set_warnings("all", "extra")

add_requires("unity_test 2.7.0")
add_requires("libuv 1.52.1", {system = false, configs = {shared = false}})

includes("bootstrap/xmake.lua")
includes("runtime/xmake.lua")
