set_project("mini")
set_version("0.1.0")
set_languages("c17")

add_rules("mode.debug", "mode.release")

set_warnings("all", "extra")

add_requires("unity_test 2.7.0")

includes("bootstrap/xmake.lua")
includes("runtime/xmake.lua")
