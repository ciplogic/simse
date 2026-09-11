@echo off
setlocal

rem ---------------------------------------------------------------------------
rem build.bat - compile an amalgamated Simse output with cl.exe.
rem
rem   build.bat [source.cpp] [output.exe]
rem
rem Defaults: simse_out.cpp in the current folder -> <name>.exe next to it.
rem The generated file includes "cppsrc/rtl/simse.hpp", so the repository root
rem (this script's folder) is added to the include path, and the RTL/native
rem static libraries from the CMake build folder are linked.
rem
rem Environment overrides:
rem   SIMSE_BUILD_DIR  folder holding simse_native.lib / simse_lib.lib
rem                    (default: <repo>\cmake-build-debug, else cmake-build-release)
rem   SIMSE_ARCH       vcvarsall target architecture (default: arm64)
rem   SIMSE_CRT        runtime-library flag (default: /MDd for cmake-build-debug,
rem                    /MD for cmake-build-release); must match the CMake build's
rem                    MSVC_RUNTIME_LIBRARY setting
rem ---------------------------------------------------------------------------

set "REPO=%~dp0"
if "%REPO:~-1%"=="\" set "REPO=%REPO:~0,-1%"
if not defined SIMSE_BUILD_DIR set "SIMSE_BUILD_DIR=%REPO%\cmake-build-debug"
set "CRT_DEFAULT=/MDd"
if not exist "%SIMSE_BUILD_DIR%\simse_native.lib" if exist "%REPO%\cmake-build-release\simse_native.lib" (
    set "SIMSE_BUILD_DIR=%REPO%\cmake-build-release"
    set "CRT_DEFAULT=/MD"
)
if not defined SIMSE_CRT set "SIMSE_CRT=%CRT_DEFAULT%"
if not defined SIMSE_ARCH set "SIMSE_ARCH=arm64"

rem --- locate Visual Studio and load its build environment ------------------
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VSPATH="
if exist "%VSWHERE%" for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -property installationPath`) do set "VSPATH=%%i"
if not defined VSPATH if exist "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" set "VSPATH=C:\Program Files\Microsoft Visual Studio\18\Community"
if not defined VSPATH (
    echo build.bat: cannot locate Visual Studio; run this from a Developer Command Prompt instead.
    exit /b 1
)
call "%VSPATH%\VC\Auxiliary\Build\vcvarsall.bat" %SIMSE_ARCH% >nul
if errorlevel 1 (
    echo build.bat: vcvarsall.bat %SIMSE_ARCH% failed.
    exit /b 1
)

rem --- arguments -------------------------------------------------------------
set "SRC=%~f1"
if not defined SRC for %%F in ("%CD%\simse_out.cpp") do set "SRC=%%~fF"
set "OUT=%~f2"
for %%F in ("%SRC%") do (
    set "BASE=%%~nF"
    if not defined OUT set "OUT=%%~dpnF.exe"
)
if not exist "%SRC%" (
    echo build.bat: source not found: "%SRC%"
    echo            generate it first, e.g. simse.exe cppsrc/compiler --module-root cppsrc/common ...
    exit /b 1
)
for %%L in (simse_native.lib simse_lib.lib) do if not exist "%SIMSE_BUILD_DIR%\%%L" (
    echo build.bat: missing "%SIMSE_BUILD_DIR%\%%L"
    echo            build the project first: cd cmake-build-debug ^&^& _msvc_build.bat
    exit /b 1
)

set "OBJDIR=%SIMSE_BUILD_DIR%\manual"
if not exist "%OBJDIR%" mkdir "%OBJDIR%"

echo build.bat: compiling "%SRC%"
echo build.bat:          -^> "%OUT%"
cl /nologo /std:c++20 /EHsc /W3 %SIMSE_CRT% /I"%REPO%" /Fo"%OBJDIR%\%BASE%.obj" /Fe"%OUT%" ^
   "%SRC%" "%SIMSE_BUILD_DIR%\simse_native.lib" "%SIMSE_BUILD_DIR%\simse_lib.lib"
if errorlevel 1 (
    echo build.bat: cl.exe failed.
    exit /b 1
)
echo build.bat: wrote "%OUT%"
exit /b 0
