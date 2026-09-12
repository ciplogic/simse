@echo off
rem Scratch: build the benchmark under all four backing combinations.
rem   tools\_bench4.bat [flags...]
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0.."
cl /nologo /std:c++20 /EHsc /W3 /I. /Fotools\bench_ss.obj /Fe:tools\bench_ss.exe %* tools\str_bench.cpp
cl /nologo /std:c++20 /EHsc /W3 /I. /Fotools\bench_sv.obj /Fe:tools\bench_sv.exe /DSIMSE_STR_STD_STRING %* tools\str_bench.cpp
cl /nologo /std:c++20 /EHsc /W3 /I. /Fotools\bench_vs.obj /Fe:tools\bench_vs.exe /DSIMSE_LIST_STD_VECTOR %* tools\str_bench.cpp
cl /nologo /std:c++20 /EHsc /W3 /I. /Fotools\bench_vv.obj /Fe:tools\bench_vv.exe /DSIMSE_LIST_STD_VECTOR /DSIMSE_STR_STD_STRING %* tools\str_bench.cpp
