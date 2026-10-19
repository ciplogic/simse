@echo off
rem ---------------------------------------------------------------------------
rem dx.bat - the developer-experience alias for the smt toolset.
rem
rem smt (bin\smt-tool) is the project CLI; dx is the same tool under the name a
rem developer reaches for: `dx init`, `dx build`, `dx run`, `dx check`, `dx clean`.
rem Everything, including the help text, lives in smt (smt.bat, bin\smt-tool\src).
rem ---------------------------------------------------------------------------
call "%~dp0smt.bat" %*
exit /b %errorlevel%
