@echo off
setlocal
rem ---------------------------------------------------------------------------
rem build.bat - thin launcher for build.js (see that file for the options).
rem
rem   build.bat                 transpile cppsrc -> simse_out.cpp, compile -> simse.exe
rem   build.bat <output.exe>    same, with a different executable name
rem   build.bat --help          list all options
rem
rem Requires the bun runtime (https://bun.sh) on PATH.
rem ---------------------------------------------------------------------------
where bun >nul 2>nul
if errorlevel 1 (
    echo build.bat: bun is required to run build.js - install it from https://bun.sh
    exit /b 1
)
bun "%~dp0build.js" %*
exit /b %errorlevel%
