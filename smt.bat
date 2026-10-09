@echo off
setlocal
rem ---------------------------------------------------------------------------
rem smt.bat - the smt toolset's launcher.
rem
rem smt is a Simse program: its source is bin\smt-tool\src\main.kt and it is
rem built into bin\smt.exe. This script builds it when that is missing, then
rem runs it - with the caller's working directory, so `smt` sees the project a
rem user is standing in.
rem
rem With the compiler built (build.bat -> simse.exe) the tool is transpiled
rem from its source; without one, the checked-in amalgamation bin\smt-tool\
rem smt_src.cpp is compiled directly - the same "from a C++ compiler alone"
rem path the compiler's own bootstrap takes.
rem
rem   smt.bat                 the command list
rem   smt.bat build           a command (a stub for now: it prints its plan)
rem   smt.bat --help          the commands and options
rem
rem Requires the bun runtime (https://bun.sh) on PATH.
rem ---------------------------------------------------------------------------
set "SMT_EXE=%~dp0bin\smt.exe"
if exist "%SMT_EXE%" (
    "%SMT_EXE%" %*
    exit /b %errorlevel%
)

where bun >nul 2>nul
if errorlevel 1 (
    echo smt.bat: bun is required to build smt - install it from https://bun.sh 1>&2
    exit /b 1
)
if not exist "%~dp0bin" mkdir "%~dp0bin"
echo smt: building bin\smt.exe 1>&2
pushd "%~dp0"
if exist "simse.exe" (
    rem The compiler is here: transpile the source and refresh the checked-in amalgamation.
    bun build.js --release --quiet --root bin/smt-tool/src --module modules/io --out bin/smt-tool/smt_src.cpp --exe bin/smt.exe 1>&2
) else (
    rem No compiler: compile the checked-in amalgamation with cl.exe alone.
    bun build.js --release --quiet --cpp bin/smt-tool/smt_src.cpp --exe bin/smt.exe 1>&2
)
set "SMT_BUILD=%errorlevel%"
popd
if not "%SMT_BUILD%"=="0" exit /b %SMT_BUILD%

"%SMT_EXE%" %*
exit /b %errorlevel%
