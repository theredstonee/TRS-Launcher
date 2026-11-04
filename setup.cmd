@echo off
set "GITBASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not exist "%GITBASH%" set "GITBASH=%ProgramFiles%\Git\bin\bash.exe"
if not exist "%GITBASH%" (
  echo Git fuer Windows fehlt. Installieren: winget install --id Git.Git -e
  exit /b 1
)
"%GITBASH%" "%~dp0setup" %*
