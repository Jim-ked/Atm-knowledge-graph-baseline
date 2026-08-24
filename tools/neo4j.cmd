@echo off
setlocal

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if exist "%PROJECT_ROOT%\tools\env.cmd" call "%PROJECT_ROOT%\tools\env.cmd"
if "%ATMKG_NEO4J_HOME%"=="" goto missing_home

set "NEO4J_COMMAND=%ATMKG_NEO4J_HOME%\bin\neo4j.bat"
if not exist "%NEO4J_COMMAND%" goto invalid_home

if /I "%~1"=="start" goto run
if /I "%~1"=="stop" goto run
if /I "%~1"=="status" goto run
if /I "%~1"=="console" goto run
goto usage

:run
call "%NEO4J_COMMAND%" %1
exit /b %ERRORLEVEL%

:missing_home
echo ERROR: ATMKG_NEO4J_HOME is not set.
echo Set ATMKG_NEO4J_HOME to the Neo4j installation directory.
goto fallback

:invalid_home
echo ERROR: Neo4j command not found: "%NEO4J_COMMAND%"
echo Set ATMKG_NEO4J_HOME to the directory that contains bin\neo4j.bat.
goto fallback

:usage
echo Usage: neo4j.cmd start^|stop^|status^|console
exit /b 2

:fallback
echo Manual fallback: cd /d "<NEO4J_HOME>\bin"
echo Then run: neo4j.bat console
exit /b 2
