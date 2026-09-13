@echo off
rem build_naive.bat - compile the naive C++ STL baseline (brc_naive.cpp) with the
rem same flags the released compiler is built with, so its timing is comparable to
rem the Simse implementation in this folder.
rem
rem   benchmarks\onebrc\build_naive.bat
rem   benchmarks\onebrc\brc_naive.exe tools\_brc_10m.txt
rem
rem Requires a Visual Studio installation (the vcvarsall path below is the one
rem tools\msvc.mjs finds via vswhere; edit it if yours differs).
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0"
cl /nologo /std:c++20 /EHsc /W3 /O2 /Ob3 /DNDEBUG brc_naive.cpp /Fe:brc_naive.exe
