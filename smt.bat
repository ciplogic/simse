@echo off
setlocal
rem ---------------------------------------------------------------------------
rem smt.bat - thin launcher for tools\smt.js (see that file for the commands).
rem
rem   smt.bat                 the command list
rem   smt.bat build           a command (a stub for now: it prints its plan)
rem   smt.bat --help          the commands and options
rem
rem Requires the bun runtime (https://bun.sh) on PATH.
rem ---------------------------------------------------------------------------
where bun >nul 2>nul
if errorlevel 1 (
    echo smt.bat: bun is required to run tools\smt.js - install it from https://bun.sh
    exit /b 1
)
bun "%~dp0tools\smt.js" %*
exit /b %errorlevel%
