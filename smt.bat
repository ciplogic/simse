@echo off
setlocal
rem ---------------------------------------------------------------------------
rem smt.bat - the smt toolset's launcher.
rem
rem smt is a Simse program (tools\smt\src\main.kt, the header there has the
rem commands). This script builds it into build\smt\smt.exe when that is
rem missing, then runs it - with the caller's working directory, so `smt` sees
rem the project a user is standing in. Rebuild after editing its sources with:
rem
rem   bun build.js --release --root tools/smt/src --module modules/io ^
rem       --out build/smt/smt.cpp --exe build/smt/smt.exe
rem
rem   smt.bat                 the command list
rem   smt.bat build           a command (a stub for now: it prints its plan)
rem   smt.bat --help          the commands and options
rem
rem Requires the bun runtime (https://bun.sh) on PATH, and the compiler: build
rem it first with build.bat (./simse.exe transpiles smt, exactly like an example).
rem ---------------------------------------------------------------------------
set "SMT_EXE=%~dp0build\smt\smt.exe"
if exist "%SMT_EXE%" (
    "%SMT_EXE%" %*
    exit /b %errorlevel%
)

where bun >nul 2>nul
if errorlevel 1 (
    echo smt.bat: bun is required to build smt - install it from https://bun.sh 1>&2
    exit /b 1
)
if not exist "%~dp0build\smt" mkdir "%~dp0build\smt"
echo smt: building build\smt\smt.exe from tools\smt\src 1>&2
pushd "%~dp0"
bun build.js --release --quiet --root tools/smt/src --module modules/io --out build/smt/smt.cpp --exe build/smt/smt.exe 1>&2
set "SMT_BUILD=%errorlevel%"
popd
if not "%SMT_BUILD%"=="0" exit /b %SMT_BUILD%

"%SMT_EXE%" %*
exit /b %errorlevel%
