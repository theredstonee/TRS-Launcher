@echo off
set "GITBASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not exist "%GITBASH%" set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
"%GITBASH%" "%~dp0start-client" %*
