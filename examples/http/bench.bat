@echo off
rem The request-rate measurement.
rem
rem   bench.bat [connections] [requestsEach] [path]
rem
rem Builds the server and the client, starts the server with a request limit equal to
rem connections * requestsEach (so it leaves on its own) and runs that many clients at once, each
rem a fully blocking client making requestsEach requests - a fresh connection per request. Both
rem sides report: each client its own round trip, the server the aggregate, which is the number
rem that matters as the connection count grows. With one connection, raise requestsEach by 10x
rem until a run passes ~500 ms.
setlocal
pushd "%~dp0..\..\.."

set PORT=8097
set CONNS=%1
set REQ=%2
set PATHQ=%3
if "%CONNS%"=="" set CONNS=1
if "%REQ%"=="" set REQ=1000
if "%PATHQ%"=="" set PATHQ=/
set /a TOTAL=%CONNS% * %REQ%

simse.exe --root examples/http/server/src --module examples/http/sockets -o examples/http/server/out.cpp
if errorlevel 1 goto :fail
simse.exe --root examples/http/client/src --module examples/http/sockets -o examples/http/client/out.cpp
if errorlevel 1 goto :fail

call bun build.js --release --cpp examples/http/server/out.cpp --exe examples/http/server/httpd.exe
if errorlevel 1 goto :fail
call bun build.js --release --cpp examples/http/client/out.cpp --exe examples/http/client/httpclient.exe
if errorlevel 1 goto :fail

start "" /b docs\examples\http\server\httpd.exe %PORT% %TOTAL%
echo bench: %CONNS% connection(s) x %REQ% requests = %TOTAL%

set /a I=0
:spawn
if %I% GEQ %CONNS% goto :ran
start "" /b docs\examples\http\client\httpclient.exe %PORT% %PATHQ% %REQ%
set /a I+=1
goto :spawn
:ran

rem Wait for the server to leave (its request limit reached), then its aggregate line is the last
rem thing printed. The poll is 1 s, and the server's own clock is what is reported, so the poll
rem granularity does not enter the numbers.
:wait
%SystemRoot%\System32\ping.exe -n 2 127.0.0.1 >nul
%SystemRoot%\System32\tasklist.exe /FI "IMAGENAME eq httpd.exe" | %SystemRoot%\System32\find.exe /i "httpd.exe" >nul
if not errorlevel 1 goto :wait

popd
exit /b 0

:fail
popd
exit /b 1
