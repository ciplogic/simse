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
        cppsrc/rtl/simse.hpp
        cppsrc/common/FileUtils.cpp
        cppsrc/common/FileUtils.h)
