@echo off
rem Scratch: build a standalone header-only probe/benchmark with cl.exe.
rem   tools\_probe.bat <name> [flags...]     (source is tools\<name>.cpp)
rem Example:
rem   tools\_probe.bat str_bench /O2 /DNDEBUG
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0.."
set NAME=%1
shift
cl /nologo /std:c++20 /EHsc /W3 /I. /Fotools\%NAME%.obj /Fe:tools\%NAME%.exe %1 %2 %3 %4 %5 %6 %7 %8 tools\%NAME%.cpp
