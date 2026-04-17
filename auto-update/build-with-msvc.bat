@echo off
REM Helper: ensure cargo + vswhere are in PATH, wire MSVC toolchain env vars,
REM then run cargo from the script's own dir. Works around Claude-spawned
REM cmd sessions not inheriting user-level PATH entries.
cd /d "%~dp0"
set "PATH=%USERPROFILE%\.cargo\bin;C:\Program Files (x86)\Microsoft Visual Studio\Installer;%PATH%"
call "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b %errorlevel%
cargo %*
