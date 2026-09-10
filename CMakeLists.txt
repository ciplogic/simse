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
        cppsrc/skelparser/SkeletonParser.cpp
        cppsrc/skelparser/SkeletonParser.h
        tests/test_support.cpp
        tests/test_support.h)

add_executable(simse cppsrc/main.cpp)
target_link_libraries(simse PRIVATE simse_lib)

add_executable(simse_tests tests/test_main.cpp)
target_link_libraries(simse_tests PRIVATE simse_lib)
target_compile_definitions(simse_tests PRIVATE
        SIMSE_FIXTURES_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixtures"
        SIMSE_SOURCE_ROOT="${CMAKE_CURRENT_SOURCE_DIR}")
