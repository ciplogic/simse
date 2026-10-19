@echo off
setlocal
rem ---------------------------------------------------------------------------
rem smt.bat - the smt toolset's launcher.
rem
rem smt is a Simse program (bin\smt-tool\src\main.kt) built into bin\smt.exe.
rem This script sets up the Visual Studio environment, builds smt when bin\smt.exe
rem is missing, then runs it with the caller's working directory.
rem
rem There is no script runtime in the loop: the toolchain is simse.exe (the
rem compiler) and cl.exe (the C++ compiler). The checked-in amalgamation
rem bin\smt-tool\smt_src.cpp is what gets compiled when simse.exe is absent.
rem
rem   smt.bat init [<dir>]         scaffold a project (dx.bat is the alias for all of this)
rem   smt.bat build                transpile and compile the project
rem   smt.bat run -- <args>        build, then run the program
rem   smt.bat check | clean        transpile only | remove the build artifacts
rem   smt.bat --help               the commands and options
rem ---------------------------------------------------------------------------
set "ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=arm64"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VSPATH="
if exist "%VSWHERE%" for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -property installationPath 2^>nul`) do set "VSPATH=%%i"
if not defined VSPATH if exist "%ProgramFiles%\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" set "VSPATH=%ProgramFiles%\Microsoft Visual Studio\18\Community"
if defined VSPATH (
    call "%VSPATH%\VC\Auxiliary\Build\vcvarsall.bat" %ARCH% >nul
) else (
    echo smt.bat: Visual Studio not found - cl.exe must be on PATH 1>&2
)

set "SMT_EXE=%~dp0bin\smt.exe"
if not exist "%SMT_EXE%" call :build
if errorlevel 1 exit /b 1
"%SMT_EXE%" %*
exit /b %errorlevel%

:build
pushd "%~dp0"
if not exist "bin" mkdir "bin"
if exist "simse.exe" (
    echo smt: transpiling bin\smt-tool\src -^> bin\smt-tool\smt_src.cpp 1>&2
    "simse.exe" --root bin/smt-tool/src --module modules/io --module modules/process -o bin/smt-tool/smt_src.cpp
    if errorlevel 1 ( popd & exit /b 1 )
)
echo smt: compiling bin\smt.exe 1>&2
cl /nologo /std:c++20 /EHsc /bigobj /MD /I. /Fe:bin\smt.exe bin\smt-tool\smt_src.cpp
set "SMT_RC=%errorlevel%"
popd
exit /b %SMT_RC%
