cmake_minimum_required(VERSION 3.24)
project(MiniWindowsLLVMCompiler LANGUAGES C CXX)

find_package(LLVM 18.1.8 EXACT REQUIRED CONFIG)
foreach(required IN ITEMS MINI_COMPILER_INPUT MINI_RUNTIME_OBJECTS MINI_EXPORTS_FILE MINI_RUNTIME_INCLUDE)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
        message(FATAL_ERROR "Missing ${required}")
    endif()
endforeach()

# Match the official LLVM development archive's release MSVC CRT. LLVM itself
# is linked from static component archives; the Windows CRT remains shared.
set(CMAKE_MSVC_RUNTIME_LIBRARY MultiThreadedDLL)
llvm_map_components_to_libnames(MINI_LLVM_LIBS core native orcjit passes x86 aarch64)
foreach(component IN LISTS MINI_LLVM_LIBS)
    if(NOT TARGET ${component})
        message(FATAL_ERROR "Missing imported LLVM component target: ${component}")
    endif()
    get_target_property(component_type ${component} TYPE)
    if(NOT component_type STREQUAL "STATIC_LIBRARY")
        message(FATAL_ERROR "LLVM component ${component} must be static; got ${component_type}")
    endif()
endforeach()
add_definitions(${LLVM_DEFINITIONS})
add_executable(minic-api "${MINI_COMPILER_INPUT}" ${MINI_RUNTIME_OBJECTS} "${MINI_EXPORTS_FILE}")
set_target_properties(minic-api PROPERTIES LINKER_LANGUAGE CXX C_STANDARD 17)
target_include_directories(minic-api SYSTEM PRIVATE ${LLVM_INCLUDE_DIRS})
target_include_directories(minic-api PRIVATE "${MINI_RUNTIME_INCLUDE}")
target_compile_options(minic-api PRIVATE /w)
# The runtime's wmain owns UTF-16 argv. Retain it even when the compiler's
# generated object also contains a main symbol.
target_link_options(minic-api PRIVATE /INCLUDE:wmain)
target_link_libraries(minic-api PRIVATE ${MINI_LLVM_LIBS})
