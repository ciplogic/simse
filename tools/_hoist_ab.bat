@echo off
rem Scratch: build the self-hosted compiler (an amalgamation + the native TUs) at
rem a chosen Str inline capacity, for the size/speed A/B.
rem   tools\_hoist_ab.bat <capacity> <out.exe> [source.cpp] [extra cl flags...]
rem Both TUs go through one cl invocation on purpose: every translation unit in a
rem binary has to agree on the Str layout, or the mismatch corrupts memory.
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0.."
set CAP=%1
set OUT=%2
set SRC=%3
if "%SRC%"=="" set SRC=cppsrc\simse_out.cpp
if not exist "tools\hoist%CAP%" mkdir "tools\hoist%CAP%"
cl /nologo /std:c++20 /EHsc /W3 /MD /O2 /DNDEBUG /I. /DSIMSE_STR_INLINE_CAPACITY=%CAP% %4 %5 %6 %7 %8 ^
   /Fo:tools\hoist%CAP%\ /Fe:%OUT% %SRC% cppsrc\native\Native.cpp cppsrc\common\common.cpp
