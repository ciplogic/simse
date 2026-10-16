@echo off
rem Build and run the Minesweeper example: transpile the app with the `minui`, `game` and `xml`
rem modules, compile the amalgamation, put the two DLLs it binds beside the executable, and run.
rem Arguments pass through (--frames N, --screenshot out.bmp, --seed N, --difficulty 0|1|2,
rem --script "x,y:L; x,y:R; x,y:M").
setlocal
pushd "%~dp0..\.."

simse.exe --root examples/minesweeper/app/src --module modules/minui --module examples/minesweeper/game --module modules/xml -o examples/minesweeper/app/out.cpp
if errorlevel 1 goto :fail

call bun build.js --release --cpp examples/minesweeper/app/out.cpp --exe examples/minesweeper/app/minesweeperdemo.exe
if errorlevel 1 goto :fail

rem Neither DLL is linked or committed: SDL2.dll comes from the ARM64 dev kit unpacked under
rem Lib/3rdparty/ (the release zip, not in the checkout), libSkiaSharp.dll from the SkiaSharp
rem NuGet package that Avalonia's own Skia build ships.
if not exist "Lib\3rdparty\SDL2-arm64\bin\SDL2.dll" (
    echo run.bat: Lib\3rdparty\SDL2-arm64 is missing - unpack the SDL2 ARM64 dev kit there
    goto :fail
)
copy /y "Lib\3rdparty\SDL2-arm64\bin\SDL2.dll" "examples\minesweeper\app\SDL2.dll" >nul
if not exist "examples\minesweeper\app\libSkiaSharp.dll" (
    copy /y "%USERPROFILE%\.nuget\packages\skiasharp.nativeassets.win32\2.88.9\runtimes\win-arm64\native\libSkiaSharp.dll" "examples\minesweeper\app\libSkiaSharp.dll" >nul
)

examples\minesweeper\app\minesweeperdemo.exe %*
set status=%errorlevel%
popd
exit /b %status%

:fail
popd
exit /b 1
