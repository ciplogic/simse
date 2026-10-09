@echo off
rem Build and run the SDL2 example: transpile the demo with the wrapper module, compile the
rem amalgamation, put SDL2.dll beside the executable, and run (pass an argument to render a few
rem frames and leave instead of waiting for Escape).
setlocal
pushd "%~dp0..\..\.."

simse.exe --root examples/sdl2/app/src --module examples/sdl2/wrapper -o examples/sdl2/app/out.cpp
if errorlevel 1 goto :fail

call bun build.js --release --cpp examples/sdl2/app/out.cpp --exe examples/sdl2/app/sdl2demo.exe
if errorlevel 1 goto :fail

copy /y "Lib\3rdparty\SDL2-arm64\bin\SDL2.dll" "docs\examples\sdl2\app\SDL2.dll" >nul

docs\examples\sdl2\app\sdl2demo.exe %*
set status=%errorlevel%
popd
exit /b %status%

:fail
popd
exit /b 1
