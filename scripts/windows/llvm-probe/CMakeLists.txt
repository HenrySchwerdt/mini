cmake_minimum_required(VERSION 3.24)
project(MiniWindowsLLVMProbe LANGUAGES CXX)

find_package(LLVM 18.1.8 EXACT REQUIRED CONFIG)

if(NOT LLVM_PACKAGE_VERSION VERSION_EQUAL "18.1.8")
    message(FATAL_ERROR "Expected LLVM 18.1.8 exactly; found ${LLVM_PACKAGE_VERSION}")
endif()

message(STATUS "LLVM package version: ${LLVM_PACKAGE_VERSION}")
message(STATUS "LLVM include directories: ${LLVM_INCLUDE_DIRS}")
message(STATUS "LLVM library directory: ${LLVM_LIBRARY_DIRS}")
message(STATUS "LLVM available libraries: ${LLVM_AVAILABLE_LIBS}")

llvm_map_components_to_libnames(MINI_LLVM_LIBS core native orcjit passes x86 aarch64)
message(STATUS "LLVM mapped static components: ${MINI_LLVM_LIBS}")

foreach(required_component IN ITEMS LLVMCore LLVMOrcJIT LLVMX86CodeGen LLVMAArch64CodeGen)
    if(NOT required_component IN_LIST LLVM_AVAILABLE_LIBS)
        message(FATAL_ERROR "LLVM package is missing required component library: ${required_component}")
    endif()
endforeach()

add_executable(mini-llvm-probe probe.cpp)
target_include_directories(mini-llvm-probe SYSTEM PRIVATE ${LLVM_INCLUDE_DIRS})
target_compile_definitions(mini-llvm-probe PRIVATE ${LLVM_DEFINITIONS})
target_link_libraries(mini-llvm-probe PRIVATE ${MINI_LLVM_LIBS})
