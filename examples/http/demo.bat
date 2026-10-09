@echo off
rem Build the socket library's two users, start the server (limited to three requests so it
rem exits by itself), and run the client against /, /json and a missing path.
setlocal
pushd "%~dp0..\..\.."

set PORT=8099

simse.exe --root examples/http/server/src --module examples/http/sockets -o examples/http/server/out.cpp
if errorlevel 1 goto :fail
simse.exe --root examples/http/client/src --module examples/http/sockets -o examples/http/client/out.cpp
if errorlevel 1 goto :fail

call bun build.js --release --cpp examples/http/server/out.cpp --exe examples/http/server/httpd.exe
if errorlevel 1 goto :fail
call bun build.js --release --cpp examples/http/client/out.cpp --exe examples/http/client/httpclient.exe
if errorlevel 1 goto :fail

rem The server blocks, so start it in the background; the client retries the connect until the
rem server is up, and the request limit makes the server leave once these three are answered.
start "" /b docs\examples\http\server\httpd.exe %PORT% 3

echo.
echo === GET /
docs\examples\http\client\httpclient.exe %PORT% /
echo === GET /json
docs\examples\http\client\httpclient.exe %PORT% /json
echo === GET /missing
docs\examples\http\client\httpclient.exe %PORT% /missing

popd
exit /b 0

:fail
popd
exit /b 1
