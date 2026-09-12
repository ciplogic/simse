@echo off
rem Scratch: compile tools\<source>.cpp against a specific RTL include root.
rem   tools\_ab.bat <include-root> <out-name> <source-name> [flags...]
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0.."
set ROOT=%1
set OUT=%2
set SRC=%3
shift
shift
shift
cl /nologo /std:c++20 /EHsc /W3 /I%ROOT% /Fotools\%OUT%.obj /Fe:tools\%OUT%.exe %1 %2 %3 %4 %5 %6 %7 %8 tools\%SRC%.cpp
