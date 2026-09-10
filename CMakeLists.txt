cmake_minimum_required(VERSION 4.3)
project(simse)

set(CMAKE_CXX_STANDARD 20)

add_executable(simse
        main.cpp
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
        cppsrc/skelparser/SkeletonParser.cpp
        cppsrc/skelparser/SkeletonParser.h)
