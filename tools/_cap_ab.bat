@echo off
rem Scratch: build and run a tools probe at a chosen Str inline capacity, so the
rem 16-vs-24 layouts and micro-benchmarks are one command each.
rem   tools\_cap_ab.bat <capacity> <source-name> [flags...]
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvarsall.bat" arm64 >nul
cd /d "%~dp0.."
set CAP=%1
set NAME=%2
shift
shift
cl /nologo /std:c++20 /EHsc /W3 /I. -DSIMSE_STR_INLINE_CAPACITY=%CAP% /O2 /DNDEBUG %1 %2 %3 %4 %5 %6 ^
   /Fo:tools\cap%CAP%_%NAME%.obj /Fe:tools\cap%CAP%_%NAME%.exe tools\%NAME%.cpp
if errorlevel 1 exit /b 1
echo --- capacity %CAP% ---
tools\cap%CAP%_%NAME%.exe
