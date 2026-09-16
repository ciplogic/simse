cmake_minimum_required(VERSION 4.3)
project(simse)

set(CMAKE_CXX_STANDARD 20)

# Str's inline capacity in bytes, NUL included (the RTL default is in
# cppsrc/rtl/strsmallvector.hpp). Lowering it shrinks every Str — and every
# aggregate holding one — at the cost of spilling more (shorter) strings to the
# heap; the value is part of the ABI, so it has to match between the compiler
# and any amalgamated output it is linked with (impl_specs/rtl-abi.md).
set(SIMSE_STR_INLINE_CAPACITY "" CACHE STRING "Override Str's inline byte capacity")
if(SIMSE_STR_INLINE_CAPACITY)
    add_compile_definitions(SIMSE_STR_INLINE_CAPACITY=${SIMSE_STR_INLINE_CAPACITY})
endif()

# The RTL and generated aggregates are 4-byte packed by default
# (specs/memory-model.md); turn this on to build with the host's default
# alignment instead (impl_specs/rtl-abi.md).
option(SIMSE_NO_PACK4 "Do not 4-byte-pack the RTL and generated aggregates" OFF)

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
        cppsrc/rtl/span.hpp
        cppsrc/rtl/strview.hpp
        cppsrc/rtl/listops.hpp
        cppsrc/rtl/strops.hpp
        cppsrc/rtl/dictops.hpp
        cppsrc/rtl/fs.hpp
        cppsrc/rtl/rtl.kt
        cppsrc/rtl/xml.kt
        cppsrc/rtl/Span.kt
        cppsrc/rtl/StrView.kt
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
        cppsrc/sema/TypeInfer.cpp
        cppsrc/sema/TypeInfer.h
        cppsrc/linear/Linear.cpp
        cppsrc/linear/Linear.h
        cppsrc/linear/Simplify.cpp
        cppsrc/linear/Simplify.h
        cppsrc/linear/ExpressionLowering.cpp
        cppsrc/linear/ExpressionLowering.h
        cppsrc/linear/LinearForm.cpp
        cppsrc/linear/LinearForm.h
        cppsrc/linear/Yield.cpp
        cppsrc/linear/Yield.h
        cppsrc/codegen/Codegen.cpp
        cppsrc/codegen/Codegen.h
        cppsrc/skelparser/SkeletonParser.cpp
        cppsrc/skelparser/SkeletonParser.h
        tests/test_support.cpp
        tests/test_support.h)

# The RTL surface the transpiler loads as a prelude (no import required) and the
# repository root used to resolve `import a.b.c` directories. The prelude is a
# *directory*: every `*.kt` in it (rtl.kt, xml.kt) is loaded into the
# shared module scope. The paths are baked in so the CLI, the build, and the
# in-process test harness agree regardless of the working directory.
target_compile_definitions(simse_lib PUBLIC
        SIMSE_DEFAULT_PRELUDE="${CMAKE_CURRENT_SOURCE_DIR}/cppsrc/rtl"
        SIMSE_SOURCE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}")

if(SIMSE_NO_PACK4)
    add_compile_definitions(SIMSE_NO_PACK4)
endif()

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

# The end-to-end slice (transpile -> compile -> run -> compare stdout) is no
# longer built here: it lives in the `stress/` folders and runs under
# `bun tools/stress.js` (see stress/README.md), so the round trip is exercised by
# the compiler under test rather than by a CMake target. The remaining targets
# below are the port's own invariants (hand-written ring vs transpiled Simse, and
# the bootstrap fixed point).
set(SIMSE_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")

# --- T11: differential scanner (hand-written C++ vs transpiled Simse) --------
# Scanner.kt is transpiled and compiled with a small hand-written driver. The
# reference driver uses the hand-written lex::Scanner. Both print the same
# canonical token dump over tests/fixtures/*.kt; the build diffs them. This is
# part of ALL so a normal build exercises the port.
set(SIMSE_SELFHOST_DIR "${CMAKE_BINARY_DIR}/selfhost")
set(SIMSE_SCANNER_GENERATED "${SIMSE_SELFHOST_DIR}/Scanner.kt.cpp")
set(SIMSE_SCANNER_FIXTURES "${SIMSE_REPO_ROOT}/tests/fixtures")

add_custom_command(
        OUTPUT "${SIMSE_SCANNER_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                -o "${SIMSE_SCANNER_GENERATED}"
        DEPENDS "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.kt"
                simse_transpile
        COMMENT "T11: transpiling Scanner.kt"
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
                -DOUT=${SIMSE_SELFHOST_DIR}/scanner.kt.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/scanner.ref.out"
                "${SIMSE_SELFHOST_DIR}/scanner.kt.out"
        DEPENDS scanner_ref scanner_simse
        COMMENT "T11: diffing hand-written and transpiled scanner token dumps"
        VERBATIM)

# --- T11: differential skeleton parser (hand-written vs transpiled) ----------
# SkeletonParser.kt is transpiled together with its transitive `import
# cppsrc.lex` (which pulls in Scanner.kt and common). Both drivers parse the
# same fixtures and print the shared tree dump; the build diffs them.
set(SIMSE_SKEL_GENERATED "${SIMSE_SELFHOST_DIR}/SkeletonParser.kt.cpp")

add_custom_command(
        OUTPUT "${SIMSE_SKEL_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/skelparser/SkeletonParser.kt"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                -o "${SIMSE_SKEL_GENERATED}"
        DEPENDS "${SIMSE_REPO_ROOT}/cppsrc/skelparser/SkeletonParser.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.kt"
                simse_transpile
        COMMENT "T11: transpiling SkeletonParser.kt"
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
                -DOUT=${SIMSE_SELFHOST_DIR}/skel.kt.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/skel.ref.out"
                "${SIMSE_SELFHOST_DIR}/skel.kt.out"
        DEPENDS skel_ref skel_simse
        COMMENT "T11: diffing hand-written and transpiled skeleton-parser dumps"
        VERBATIM)

# --- T19: differential parser (hand-written vs transpiled) -------------------
# Parser.kt is transpiled together with its transitive `import cppsrc.lex`.
# Both drivers parse the same fixtures and print the shared XmlNode dump. The
# reference driver also checks itself against tests/golden/*.astxml.expected, so
# this step proves `reference == golden` and `simse == reference`.
set(SIMSE_PARSER_GENERATED "${SIMSE_SELFHOST_DIR}/Parser.kt.cpp")

add_custom_command(
        OUTPUT "${SIMSE_PARSER_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.kt"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                -o "${SIMSE_PARSER_GENERATED}"
        DEPENDS "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.kt"
                simse_transpile
        COMMENT "T19: transpiling Parser.kt"
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
                -DOUT=${SIMSE_SELFHOST_DIR}/parser.kt.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/parser.ref.out"
                "${SIMSE_SELFHOST_DIR}/parser.kt.out"
        DEPENDS parser_ref parser_simse
        COMMENT "T19: diffing hand-written and transpiled parser XmlNode dumps"
        VERBATIM)

# --- T21: differential sema (hand-written vs transpiled) ---------------------
# Sema.kt is transpiled together with its transitive `import cppsrc.parser`
# (which pulls in Scanner.kt and common) and the rest of its own package: the
# arity check asks the packing rule, which lives in TypeInfer.kt, exactly as the
# hand-written Sema.cpp asks `sema::isPackTarget` from TypeInfer.cpp. Both drivers
# print the diagnostics for the same fixtures; the reference driver also checks
# itself against tests/golden/*.sema.expected, so this proves `reference == golden`
# and `simse == reference`.
set(SIMSE_SEMA_GENERATED "${SIMSE_SELFHOST_DIR}/Sema.kt.cpp")
add_custom_command(
        OUTPUT "${SIMSE_SEMA_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.kt"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/parser"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/sema"
                -o "${SIMSE_SEMA_GENERATED}"
        DEPENDS
                "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/sema/TypeInfer.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.kt"
                simse_transpile
        COMMENT "T21: transpiling Sema.kt"
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
                -DOUT=${SIMSE_SELFHOST_DIR}/sema.kt.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/sema.ref.out"
                "${SIMSE_SELFHOST_DIR}/sema.kt.out"
        DEPENDS sema_ref sema_simse
        COMMENT "T21: diffing hand-written and transpiled sema diagnostics"
        VERBATIM)

# --- T22: differential codegen (hand-written vs transpiled) ------------------
# Codegen.kt is transpiled together with its transitive `import cppsrc.sema`
# (which pulls in the parser, scanner, and common/xmlutil). Both drivers emit
# C++ for the same fixtures; the reference driver also checks itself against
# tests/golden/*.cpp.expected, so this proves `reference == golden` and
# `simse == reference`.
set(SIMSE_CODEGEN_GENERATED "${SIMSE_SELFHOST_DIR}/Codegen.kt.cpp")
add_custom_command(
        OUTPUT "${SIMSE_CODEGEN_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_SELFHOST_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile>
                "${SIMSE_REPO_ROOT}/cppsrc/codegen/Codegen.kt"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/common"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/lex"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/parser"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/sema"
                --module-root "${SIMSE_REPO_ROOT}/cppsrc/linear"
                -o "${SIMSE_CODEGEN_GENERATED}"
        DEPENDS
                "${SIMSE_REPO_ROOT}/cppsrc/codegen/Codegen.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/sema/TypeInfer.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/linear/ExpressionLowering.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/linear/Linear.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/linear/LinearForm.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/linear/Yield.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/linear/Simplify.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/common/common.kt"
                "${SIMSE_REPO_ROOT}/cppsrc/common/xmlutil.kt"
                simse_transpile
        COMMENT "T22: transpiling Codegen.kt"
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
                -DOUT=${SIMSE_SELFHOST_DIR}/codegen.kt.out
                -DWORKDIR=${SIMSE_REPO_ROOT}
                -P "${SIMSE_REPO_ROOT}/tests/run_capture.cmake"
        COMMAND "${CMAKE_COMMAND}" -E compare_files --ignore-eol
                "${SIMSE_SELFHOST_DIR}/codegen.ref.out"
                "${SIMSE_SELFHOST_DIR}/codegen.kt.out"
        DEPENDS codegen_ref codegen_simse
        COMMENT "T22: diffing hand-written and transpiled C++ emission"
        VERBATIM)

# --- T23: stage-1 self-hosted compiler ---------------------------------------
# The whole compiler source set is rooted at cppsrc/compiler/Driver.kt (its
# imports pull in the scanner, parser, sema, codegen, and common/xmlutil). The
# two-step bootstrap uses fixed names:
#   1. the C++ simse_transpile emits the compiler's C++ as `simse_out.cpp`;
#   2. that file is kept as `simse_out1.cpp` and compiled into simse_stage1;
#   3. simse_stage1 transpiles the same source set into `simse_out.cpp` again;
#   4. the kept `simse_out1.cpp` and the regenerated `simse_out.cpp` must be
#      byte-identical (the fixed point).
# Everything runs with the repository root as the working directory so
# import/prelude paths and the source-map comments are identical in both
# invocations.
set(SIMSE_ALL_MIRRORS
        "${SIMSE_REPO_ROOT}/cppsrc/compiler/Driver.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/codegen/Codegen.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/sema/Sema.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/sema/TypeInfer.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/linear/ExpressionLowering.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/linear/Linear.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/linear/LinearForm.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/linear/Yield.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/linear/Simplify.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/parser/Parser.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/lex/Scanner.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/skelparser/SkeletonParser.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/common/common.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/common/xmlutil.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/Span.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/StrView.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/astxml.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/rtl.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/xml.kt"
        "${SIMSE_REPO_ROOT}/cppsrc/rtl/fs.kt")
set(SIMSE_STAGE1_DIR "${CMAKE_BINARY_DIR}/stage1")
set(SIMSE_STAGE1_GEN_DIR "${SIMSE_STAGE1_DIR}/gen")
set(SIMSE_STAGE1_RUN_DIR "${SIMSE_STAGE1_DIR}/run")
set(SIMSE_STAGE1_INPUT "cppsrc/compiler/Driver.kt")
set(SIMSE_STAGE1_GENERATED "${SIMSE_STAGE1_GEN_DIR}/simse_out.cpp")
set(SIMSE_STAGE1_KEPT "${SIMSE_STAGE1_GEN_DIR}/simse_out1.cpp")
set(SIMSE_STAGE1_REGEN "${SIMSE_STAGE1_RUN_DIR}/simse_out.cpp")
set(SIMSE_STAGE1_FIXTURE "stress/language-tour/src/main.kt")
set(SIMSE_STAGE1_FIXTURE_REF "${SIMSE_STAGE1_DIR}/fixture_ref.cpp")
set(SIMSE_STAGE1_FIXTURE_B "${SIMSE_STAGE1_DIR}/fixture_b.cpp")

# The module roots that hold the compiler source set, in dependency order so the
# amalgamated translation unit compiles. `import` never adds files; the scanner
# includes every `.kt` under these roots (and the explicit Driver.kt).
set(SIMSE_COMPILER_ROOTS
        --module-root cppsrc/common
        --module-root cppsrc/lex
        --module-root cppsrc/skelparser
        --module-root cppsrc/parser
        --module-root cppsrc/sema
        --module-root cppsrc/linear
        --module-root cppsrc/codegen
        --module-root cppsrc/compiler)

add_custom_command(
        OUTPUT "${SIMSE_STAGE1_GENERATED}"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_STAGE1_GEN_DIR}"
        COMMAND $<TARGET_FILE:simse_transpile> "${SIMSE_STAGE1_INPUT}"
                ${SIMSE_COMPILER_ROOTS}
                -o "${SIMSE_STAGE1_GENERATED}" --prelude cppsrc/rtl
        WORKING_DIRECTORY "${SIMSE_REPO_ROOT}"
        DEPENDS ${SIMSE_ALL_MIRRORS} simse_transpile
        COMMENT "Step 1: transpiling the compiler source set into simse_out.cpp"
        VERBATIM)

# Keep the step-1 output under the comparison name; the stage-1 binary is
# compiled from this kept copy, exactly like "rename it, compile it".
add_custom_command(
        OUTPUT "${SIMSE_STAGE1_KEPT}"
        COMMAND "${CMAKE_COMMAND}" -E copy
                "${SIMSE_STAGE1_GENERATED}" "${SIMSE_STAGE1_KEPT}"
        DEPENDS "${SIMSE_STAGE1_GENERATED}"
        COMMENT "Step 2: keeping simse_out.cpp as simse_out1.cpp"
        VERBATIM)

add_executable(simse_stage1 "${SIMSE_STAGE1_KEPT}")
target_include_directories(simse_stage1 PRIVATE "${SIMSE_REPO_ROOT}")
target_link_libraries(simse_stage1 PRIVATE simse_native)
set_target_properties(simse_stage1 PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${SIMSE_STAGE1_DIR}")

add_custom_target(stage1_check ALL
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${SIMSE_STAGE1_RUN_DIR}"
        COMMAND $<TARGET_FILE:simse_stage1> "${SIMSE_STAGE1_INPUT}"
                ${SIMSE_COMPILER_ROOTS}
                -o "${SIMSE_STAGE1_REGEN}" --prelude cppsrc/rtl
        COMMAND "${CMAKE_COMMAND}" -E compare_files
                "${SIMSE_STAGE1_KEPT}" "${SIMSE_STAGE1_REGEN}"
        # Also check the stage-1 driver on a real program fixture against the
        # C++ transpiler's output for the same input.
        COMMAND $<TARGET_FILE:simse_transpile> "${SIMSE_STAGE1_FIXTURE}"
                -o "${SIMSE_STAGE1_FIXTURE_REF}" --prelude cppsrc/rtl
        COMMAND $<TARGET_FILE:simse_stage1> "${SIMSE_STAGE1_FIXTURE}"
                -o "${SIMSE_STAGE1_FIXTURE_B}" --prelude cppsrc/rtl
        COMMAND "${CMAKE_COMMAND}" -E compare_files
                "${SIMSE_STAGE1_FIXTURE_REF}" "${SIMSE_STAGE1_FIXTURE_B}"
        WORKING_DIRECTORY "${SIMSE_REPO_ROOT}"
        DEPENDS simse_stage1
        COMMENT "T23: checking the two-step bootstrap (simse_out1.cpp == simse_out.cpp)"
        VERBATIM)
