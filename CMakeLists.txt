cmake_minimum_required(VERSION 4.3)
project(simse)

set(CMAKE_CXX_STANDARD 20)

# All project code builds once into a static library; the driver and the test
# runner are thin executables that link it.
add_library(simse_lib STATIC
        cppsrc/Compiler.cpp
        cppsrc/Compiler.h
        cppsrc/lex/Scanner.cpp
        cppsrc/lex/Scanner.h
        cppsrc/rtl/simse.hpp
        cppsrc/rtl/types.hpp
        cppsrc/rtl/containers.hpp
        cppsrc/rtl/listops.hpp
        cppsrc/rtl/rtl.simse
        cppsrc/rtl/optional.hpp
        cppsrc/rtl/functional.hpp
        cppsrc/rtl/result.hpp
        cppsrc/rtl/xml.hpp
        cppsrc/common/common.cpp
        cppsrc/common/common.h
        cppsrc/ast/Ast.cpp
        cppsrc/ast/Ast.h
        cppsrc/parser/Parser.cpp
        cppsrc/parser/Parser.h
        cppsrc/sema/Sema.cpp
        cppsrc/sema/Sema.h
        cppsrc/codegen/Codegen.cpp
        cppsrc/codegen/Codegen.h
        cppsrc/skelparser/SkeletonParser.cpp
        cppsrc/skelparser/SkeletonParser.h
        tests/test_support.cpp
        tests/test_support.h)

# The RTL surface the transpiler loads as a prelude (no import required). The
# path is baked in so the CLI and the in-process test harness agree.
target_compile_definitions(simse_lib PUBLIC
        SIMSE_DEFAULT_PRELUDE="${CMAKE_CURRENT_SOURCE_DIR}/cppsrc/rtl/rtl.simse")

add_executable(simse cppsrc/main.cpp)
target_link_libraries(simse PRIVATE simse_lib)

add_executable(simse_transpile cppsrc/codegen/TranspileMain.cpp)
target_link_libraries(simse_transpile PRIVATE simse_lib)

# Hand-written native implementations callable from Simse via `native fun`.
add_library(simse_native STATIC
        cppsrc/native/Native.cpp
        cppsrc/native/Native.h)
target_link_libraries(simse_native PUBLIC simse_lib)

add_executable(simse_tests tests/test_main.cpp)
target_link_libraries(simse_tests PRIVATE simse_lib)
target_compile_definitions(simse_tests PRIVATE
        SIMSE_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures"
        SIMSE_SOURCE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}")

# --- T8: end-to-end slice (transpile -> compile -> run -> compare) -----------
# Each fixture below is transpiled by simse_transpile, compiled as a normal
# executable, run, and its stdout compared to a golden. The generated file
# `#include`s "cppsrc/rtl/simse.hpp", so the repo root is added as an include
# directory. Everything is part of ALL so a default build exercises the round
# trip and it cannot silently rot.
set(SIMSE_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
set(SIMSE_E2E_DIR "${CMAKE_CURRENT_BINARY_DIR}/e2e")

foreach(e2e_name IN ITEMS emit_hello emit_shapes emit_generics native_readfile emit_containers)
    set(e2e_fixture "${SIMSE_REPO_ROOT}/tests/fixtures/${e2e_name}.simse")
    set(e2e_generated "${SIMSE_E2E_DIR}/${e2e_name}.cpp")
    set(e2e_expected "${SIMSE_REPO_ROOT}/tests/golden/${e2e_name}.stdout.expected")
    set(e2e_actual "${SIMSE_E2E_DIR}/${e2e_name}.stdout.actual")

    add_custom_command(
            OUTPUT "${e2e_generated}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_E2E_DIR}"
            COMMAND $<TARGET_FILE:simse_transpile> "${e2e_fixture}" -o "${e2e_generated}"
            DEPENDS "${e2e_fixture}"
            COMMENT "T8: transpiling ${e2e_name}.simse"
            VERBATIM)

    add_executable(e2e_${e2e_name} "${e2e_generated}")
    target_include_directories(e2e_${e2e_name} PRIVATE "${SIMSE_REPO_ROOT}")
    if(e2e_name STREQUAL "native_readfile")
        target_link_libraries(e2e_${e2e_name} PRIVATE simse_native)
    endif()
    set_target_properties(e2e_${e2e_name} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${SIMSE_E2E_DIR}")

    add_custom_target(e2e_${e2e_name}_check ALL
            COMMAND "${CMAKE_COMMAND}"
                    -DEXE=$<TARGET_FILE:e2e_${e2e_name}>
                    -DOUT=${e2e_actual}
                    -DWORKDIR=${SIMSE_REPO_ROOT}
                    -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
            COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                    "${e2e_actual}" "${e2e_expected}"
            DEPENDS e2e_${e2e_name}
            COMMENT "T8: running e2e_${e2e_name} and comparing stdout"
            VERBATIM)
endforeach()
