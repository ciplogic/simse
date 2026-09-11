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
        cppsrc/rtl/cursor.hpp
        cppsrc/rtl/listops.hpp
        cppsrc/rtl/strops.hpp
        cppsrc/rtl/dictops.hpp
        cppsrc/rtl/fs.hpp
        cppsrc/rtl/rtl.simse
        cppsrc/rtl/xml.simse
        cppsrc/rtl/Cursor.simse
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

# The RTL surface the transpiler loads as a prelude (no import required) and the
# repository root used to resolve `import a.b.c` directories. The prelude is a
# *directory*: every `*.simse` in it (rtl.simse, xml.simse) is loaded into the
# shared module scope. The paths are baked in so the CLI, the build, and the
# in-process test harness agree regardless of the working directory.
target_compile_definitions(simse_lib PUBLIC
        SIMSE_DEFAULT_PRELUDE="${CMAKE_CURRENT_SOURCE_DIR}/cppsrc/rtl"
        SIMSE_SOURCE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}")

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
        SIMSE_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures")

# --- T8: end-to-end slice (transpile -> compile -> run -> compare) -----------
# Each fixture below is transpiled by simse_transpile, compiled as a normal
# executable, run, and its stdout compared to a golden. The generated file
# `#include`s "cppsrc/rtl/simse.hpp", so the repo root is added as an include
# directory. Everything is part of ALL so a default build exercises the round
# trip and it cannot silently rot.
set(SIMSE_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
set(SIMSE_E2E_DIR "${CMAKE_CURRENT_BINARY_DIR}/e2e")

foreach(e2e_name IN ITEMS emit_hello emit_shapes emit_generics native_readfile emit_containers emit_lang emit_xmlnode emit_cursor emit_str emit_lambda emit_dict emit_main_args main_program)
    if(e2e_name STREQUAL "main_program")
        # The runnable sample driver lives at cppsrc/main.simse, not under tests/.
        set(e2e_fixture "${SIMSE_REPO_ROOT}/cppsrc/main.simse")
    else()
        set(e2e_fixture "${SIMSE_REPO_ROOT}/tests/fixtures/${e2e_name}.simse")
    endif()
    # `emit_main_args` exercises the argv form of `main`; pass it one argument.
    set(e2e_extra_args)
    if(e2e_name STREQUAL "emit_main_args")
        set(e2e_extra_args -DEXE_ARGS=alpha)
    endif()
    # `main_program` is cppsrc/main.simse: it imports the `lex` and `common`
    # packages, so those module roots are scanned. The fixtures declare
    # `package fixtures` and import nothing.
    set(e2e_roots)
    if(e2e_name STREQUAL "main_program")
        set(e2e_roots --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex")
    endif()
    set(e2e_generated "${SIMSE_E2E_DIR}/${e2e_name}.cpp")
    set(e2e_expected "${SIMSE_REPO_ROOT}/tests/golden/${e2e_name}.stdout.expected")
    set(e2e_actual "${SIMSE_E2E_DIR}/${e2e_name}.stdout.actual")

    add_custom_command(
            OUTPUT "${e2e_generated}"
            COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_E2E_DIR}"
            COMMAND $<TARGET_FILE:simse_transpile> "${e2e_fixture}" ${e2e_roots} -o "${e2e_generated}"
            DEPENDS "${e2e_fixture}" simse_transpile
            COMMENT "T8: transpiling ${e2e_name}.simse"
            VERBATIM)

    add_executable(e2e_${e2e_name} "${e2e_generated}")
    target_include_directories(e2e_${e2e_name} PRIVATE "${SIMSE_REPO_ROOT}")
    if(e2e_name STREQUAL "native_readfile" OR e2e_name STREQUAL "main_program")
        target_link_libraries(e2e_${e2e_name} PRIVATE simse_native)
    endif()
    set_target_properties(e2e_${e2e_name} PROPERTIES
            RUNTIME_OUTPUT_DIRECTORY "${SIMSE_E2E_DIR}")

    add_custom_target(e2e_${e2e_name}_check ALL
            COMMAND "${CMAKE_COMMAND}"
                    -DEXE=$<TARGET_FILE:e2e_${e2e_name}>
                    ${e2e_extra_args}
                    -DOUT=${e2e_actual}
                    -DWORKDIR=${SIMSE_REPO_ROOT}
                    -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
            COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                    "${e2e_actual}" "${e2e_expected}"
            DEPENDS e2e_${e2e_name}
            COMMENT "T8: running e2e_${e2e_name} and comparing stdout"
            VERBATIM)
endforeach()

# --- T11: differential scanner (hand-written C++ vs transpiled Simse) --------
# Scanner.simse is transpiled and compiled with a small hand-written driver. The
# reference driver uses the hand-written lex::Scanner. Both print the same
# canonical token dump over tests/fixtures/*.simse; the build diffs them. This is
# part of ALL so a normal build exercises the port.
set(SIMSE_SELFHOST_DIR "${CMAKE_BINARY_DIR}/selfhost")
set(SIMSE_SCANNER_GENERATED "${SIMSE_SELFHOST_DIR}/Scanner.simse.cpp")
set(SIMSE_SCANNER_FIXTURES "${SIMSE_REPO_ROOT}/tests/fixtures")

add_custom_command(
        OUTPUT "${SIMSE_SCANNER_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.simse"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                -o "${SIMSE_SCANNER_GENERATED}"
        DEPENDS "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/StrView.simse"
                simse_transpile
        COMMENT "T11: transpiling Scanner.simse"
        VERBATIM)

add_executable(scanner_ref tests/scanner_ref_main.cpp)
target_link_libraries(scanner_ref PRIVATE simse_lib)

add_executable(scanner_simse tests/scanner_simse_main.cpp "${SIMSE_SCANNER_GENERATED}")
# The generated file is pulled in by the driver's `#include`, so it must not be
# compiled as its own translation unit; marker keeps it a build dependency only.
set_source_files_properties("${SIMSE_SCANNER_GENERATED}" PROPERTIES HEADER_FILE_ONLY TRUE)
target_include_directories(scanner_simse PRIVATE "${SIMSE_SELFHOST_DIR}" "${SIMSE_REPO_ROOT}")
target_link_libraries(scanner_simse PRIVATE simse_native)

set_target_properties(scanner_ref scanner_simse PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_SELFHOST_DIR}")

add_custom_target(scanner_diff ALL
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:scanner_ref>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/scanner.ref.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:scanner_simse>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/scanner.simse.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/scanner.ref.out"
                "${SIMSE_SELFHOST_DIR}/scanner.simse.out"
        DEPENDS scanner_ref scanner_simse
        COMMENT "T11: diffing hand-written and transpiled scanner token dumps"
        VERBATIM)

# --- T11: differential skeleton parser (hand-written vs transpiled) ----------
# SkeletonParser.simse is transpiled together with its transitive `import
# cppsrc.lex` (which pulls in Scanner.simse and common). Both drivers parse the
# same fixtures and print the shared tree dump; the build diffs them.
set(SIMSE_SKEL_GENERATED "${SIMSE_SELFHOST_DIR}/SkeletonParser.simse.cpp")

add_custom_command(
        OUTPUT "${SIMSE_SKEL_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/skelparser/SkeletonParser.simse"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                -o "${SIMSE_SKEL_GENERATED}"
        DEPENDS "${SIMSE_REPO_ROOT}/cppsrc/skelparser/SkeletonParser.simse" simse_transpile
        COMMENT "T11: transpiling SkeletonParser.simse"
        VERBATIM)

add_executable(skel_ref tests/skel_ref_main.cpp)
target_link_libraries(skel_ref PRIVATE simse_lib)

add_executable(skel_simse tests/skel_simse_main.cpp "${SIMSE_SKEL_GENERATED}")
set_source_files_properties("${SIMSE_SKEL_GENERATED}" PROPERTIES HEADER_FILE_ONLY TRUE)
target_include_directories(skel_simse PRIVATE "${SIMSE_SELFHOST_DIR}" "${SIMSE_REPO_ROOT}")
target_link_libraries(skel_simse PRIVATE simse_native)

set_target_properties(skel_ref skel_simse PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_SELFHOST_DIR}")

add_custom_target(skel_diff ALL
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:skel_ref>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/skel.ref.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:skel_simse>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/skel.simse.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/skel.ref.out"
                "${SIMSE_SELFHOST_DIR}/skel.simse.out"
        DEPENDS skel_ref skel_simse
        COMMENT "T11: diffing hand-written and transpiled skeleton-parser dumps"
        VERBATIM)

# --- T19: differential parser (hand-written vs transpiled) -------------------
# Parser.simse is transpiled together with its transitive `import cppsrc.lex`.
# Both drivers parse the same fixtures and print the shared XmlNode dump. The
# reference driver also checks itself against tests/golden/*.astxml.expected, so
# this step proves `reference == golden` and `simse == reference`.
set(SIMSE_PARSER_GENERATED "${SIMSE_SELFHOST_DIR}/Parser.simse.cpp")

add_custom_command(
        OUTPUT "${SIMSE_PARSER_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.simse"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                -o "${SIMSE_PARSER_GENERATED}"
        DEPENDS "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.simse" simse_transpile
        COMMENT "T19: transpiling Parser.simse"
        VERBATIM)

add_executable(parser_ref tests/parser_ref_main.cpp)
target_link_libraries(parser_ref PRIVATE simse_lib)

add_executable(parser_simse tests/parser_simse_main.cpp "${SIMSE_PARSER_GENERATED}")
set_source_files_properties("${SIMSE_PARSER_GENERATED}" PROPERTIES HEADER_FILE_ONLY TRUE)
target_include_directories(parser_simse PRIVATE "${SIMSE_SELFHOST_DIR}" "${SIMSE_REPO_ROOT}")
target_link_libraries(parser_simse PRIVATE simse_native)

set_target_properties(parser_ref parser_simse PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_SELFHOST_DIR}")

add_custom_target(parser_diff ALL
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:parser_ref>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/parser.ref.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:parser_simse>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/parser.simse.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/parser.ref.out"
                "${SIMSE_SELFHOST_DIR}/parser.simse.out"
        DEPENDS parser_ref parser_simse
        COMMENT "T19: diffing hand-written and transpiled parser XmlNode dumps"
        VERBATIM)

# --- T21: differential sema (hand-written vs transpiled) ---------------------
# Sema.simse is transpiled together with its transitive `import cppsrc.parser`
# (which pulls in Scanner.simse and common). Both drivers print the diagnostics
# for the same fixtures; the reference driver also checks itself against
# tests/golden/*.sema.expected, so this proves `reference == golden` and
# `simse == reference`.
set(SIMSE_SEMA_GENERATED "${SIMSE_SELFHOST_DIR}/Sema.simse.cpp")
add_custom_command(
        OUTPUT "${SIMSE_SEMA_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.simse"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/parser"
                -o "${SIMSE_SEMA_GENERATED}"
        DEPENDS
                "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/StrView.simse"
                simse_transpile
        COMMENT "T21: transpiling Sema.simse"
        VERBATIM)

add_executable(sema_ref tests/sema_ref_main.cpp)
target_link_libraries(sema_ref PRIVATE simse_lib)

add_executable(sema_simse tests/sema_simse_main.cpp "${SIMSE_SEMA_GENERATED}")
set_source_files_properties("${SIMSE_SEMA_GENERATED}" PROPERTIES HEADER_FILE_ONLY TRUE)
target_include_directories(sema_simse PRIVATE "${SIMSE_SELFHOST_DIR}" "${SIMSE_REPO_ROOT}")
target_compile_definitions(sema_simse PRIVATE
        SIMSE_SEMA_PRELUDE="${SIMSE_REPO_ROOT}/cppsrc/rtl")
target_link_libraries(sema_simse PRIVATE simse_native)

set_target_properties(sema_ref sema_simse PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_SELFHOST_DIR}")

add_custom_target(sema_diff ALL
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:sema_ref>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/sema.ref.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:sema_simse>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/sema.simse.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/sema.ref.out"
                "${SIMSE_SELFHOST_DIR}/sema.simse.out"
        DEPENDS sema_ref sema_simse
        COMMENT "T21: diffing hand-written and transpiled sema diagnostics"
        VERBATIM)

# --- T22: differential codegen (hand-written vs transpiled) ------------------
# Codegen.simse is transpiled together with its transitive `import cppsrc.sema`
# (which pulls in the parser, scanner, and common/xmlutil). Both drivers emit
# C++ for the same fixtures; the reference driver also checks itself against
# tests/golden/*.cpp.expected, so this proves `reference == golden` and
# `simse == reference`.
set(SIMSE_CODEGEN_GENERATED "${SIMSE_SELFHOST_DIR}/Codegen.simse.cpp")
add_custom_command(
        OUTPUT "${SIMSE_CODEGEN_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/codegen/Codegen.simse"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/parser"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/sema"
                -o "${SIMSE_CODEGEN_GENERATED}"
        DEPENDS
                "${SIMSE_REPO_ROOT}/cppsrc/codegen/Codegen.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/StrView.simse"
                "${SIMSE_REPO_ROOT}/cppsrc/common/xmlutil.simse"
                simse_transpile
        COMMENT "T22: transpiling Codegen.simse"
        VERBATIM)

add_executable(codegen_ref tests/codegen_ref_main.cpp)
target_link_libraries(codegen_ref PRIVATE simse_lib)

add_executable(codegen_simse tests/codegen_simse_main.cpp "${SIMSE_CODEGEN_GENERATED}")
set_source_files_properties("${SIMSE_CODEGEN_GENERATED}" PROPERTIES HEADER_FILE_ONLY TRUE)
target_include_directories(codegen_simse PRIVATE "${SIMSE_SELFHOST_DIR}" "${SIMSE_REPO_ROOT}")
target_compile_definitions(codegen_simse PRIVATE
        SIMSE_SEMA_PRELUDE="${SIMSE_REPO_ROOT}/cppsrc/rtl")
target_link_libraries(codegen_simse PRIVATE simse_native)

set_target_properties(codegen_ref codegen_simse PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_SELFHOST_DIR}")

add_custom_target(codegen_diff ALL
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:codegen_ref>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/codegen.ref.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}"
                -DEXE=$<TARGET_FILE:codegen_simse>
                -DEXE_ARGS=${SIMSE_SCANNER_FIXTURES}
                -DOUT=${SIMSE_SELFHOST_DIR}/codegen.simse.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/codegen.ref.out"
                "${SIMSE_SELFHOST_DIR}/codegen.simse.out"
        DEPENDS codegen_ref codegen_simse
        COMMENT "T22: diffing hand-written and transpiled C++ emission"
        VERBATIM)

# --- T23: stage-1 self-hosted compiler ---------------------------------------
# The whole compiler source set is rooted at cppsrc/compiler/Driver.simse (its
# imports pull in the scanner, parser, sema, codegen, and common/xmlutil). The
# C++ simse_transpile emits compiler_stage1.cpp, which is compiled into
# simse_stage1; running simse_stage1 over the same source set must reproduce
# compiler_stage1.cpp byte-for-byte (the fixed point). Everything runs with the
# repository root as the working directory so import/prelude paths and the
# source-map comments are identical in both invocations.
set(SIMSE_ALL_MIRRORS
        "${SIMSE_REPO_ROOT}/cppsrc/compiler/Driver.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/codegen/Codegen.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/skelparser/SkeletonParser.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/common/common.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/common/StrView.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/common/xmlutil.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/Cursor.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/rtl.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/xml.simse"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/fs.simse")
set(SIMSE_STAGE1_DIR "${CMAKE_BINARY_DIR}/stage1")
set(SIMSE_STAGE1_INPUT "cppsrc/compiler/Driver.simse")
set(SIMSE_STAGE1_GENERATED "${SIMSE_STAGE1_DIR}/compiler_stage1.cpp")
set(SIMSE_STAGE1_REGEN "${SIMSE_STAGE1_DIR}/compiler_stage1b.cpp")
set(SIMSE_STAGE1_FIXTURE "tests/fixtures/emit_lang.simse")
set(SIMSE_STAGE1_FIXTURE_REF "${SIMSE_STAGE1_DIR}/fixture_ref.cpp")
set(SIMSE_STAGE1_FIXTURE_B "${SIMSE_STAGE1_DIR}/fixture_b.cpp")

# The module roots that hold the compiler source set, in dependency order so the
# amalgamated translation unit compiles. `import` never adds files; the scanner
# includes every `.simse` under these roots (and the explicit Driver.simse).
set(SIMSE_COMPILER_ROOTS
        --module-root cppsrc/common
        --module-root cppsrc/lex
        --module-root cppsrc/skelparser
        --module-root cppsrc/parser
        --module-root cppsrc/sema
        --module-root cppsrc/codegen
        --module-root cppsrc/compiler)

add_custom_command(
        OUTPUT "${SIMSE_STAGE1_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_STAGE1_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile> "${SIMSE_STAGE1_INPUT}"
                ${SIMSE_COMPILER_ROOTS}
                -o "${SIMSE_STAGE1_GENERATED}" --prelude cppsrc/rtl
        WORKING_DIRECTORY "${SIMSE_REPO_ROOT}"
        DEPENDS ${SIMSE_ALL_MIRRORS} simse_transpile
        COMMENT "T23: transpiling the compiler source set with the C++ transpiler"
        VERBATIM)

add_executable(simse_stage1 "${SIMSE_STAGE1_GENERATED}")
target_include_directories(simse_stage1 PRIVATE "${SIMSE_REPO_ROOT}")
target_link_libraries(simse_stage1 PRIVATE simse_native)
set_target_properties(simse_stage1 PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_STAGE1_DIR}")

add_custom_target(stage1_check ALL
        COMMAND $<TARGET_FILE:simse_stage1> "${SIMSE_STAGE1_INPUT}"
                ${SIMSE_COMPILER_ROOTS}
                -o "${SIMSE_STAGE1_REGEN}" --prelude cppsrc/rtl
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_STAGE1_GENERATED}" "${SIMSE_STAGE1_REGEN}"
        # Also check the stage-1 driver on a real program fixture against the
        # C++ transpiler's output for the same input.
        COMMAND $<TARGET_FILE:simse_transpile> "${SIMSE_STAGE1_FIXTURE}"
                -o "${SIMSE_STAGE1_FIXTURE_REF}" --prelude cppsrc/rtl
        COMMAND $<TARGET_FILE:simse_stage1> "${SIMSE_STAGE1_FIXTURE}"
                -o "${SIMSE_STAGE1_FIXTURE_B}" --prelude cppsrc/rtl
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_STAGE1_FIXTURE_REF}" "${SIMSE_STAGE1_FIXTURE_B}"
        WORKING_DIRECTORY "${SIMSE_REPO_ROOT}"
        DEPENDS simse_stage1
        COMMENT "T23: checking the stage-1 fixed point"
        VERBATIM)
