@echo off
setlocal
rem ---------------------------------------------------------------------------
rem stress.bat - thin launcher for tools\stress.js (see that file for options).
rem
rem   stress.bat                  transpile, compile and run every stress case
rem   stress.bat --filter strings  one case
rem   stress.bat --list           what the corpus contains
rem   stress.bat --help           all options
rem
rem The cases live in stress\<name>\ (src\ plus what the program must print), and
rem the compiler under test is .\simse.exe - build it with build.bat first.
rem
rem Requires the bun runtime (https://bun.sh) on PATH.
rem ---------------------------------------------------------------------------
where bun >nul 2>nul
if errorlevel 1 (
    echo stress.bat: bun is required to run tools\stress.js - install it from https://bun.sh
    exit /b 1
)
bun "%~dp0tools\stress.js" %*
exit /b %errorlevel%
