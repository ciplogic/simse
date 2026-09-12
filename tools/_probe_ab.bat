@echo off
rem Scratch: compile tools\<name>.cpp against a specific RTL header tree.
rem   tools\_probe_ab.bat <name> <include-root> [flags...]
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0.."
set NAME=%1
set ROOT=%2
shift
shift
cl /nologo /std:c++20 /EHsc /W3 /I%ROOT% /Fe:tools\%NAME%_ab.exe %1 %2 %3 %4 %5 %6 %7 %8 tools\%NAME%.cpp
