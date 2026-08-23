@echo off
setlocal

set "CONTAINER=atmkg-neo4j-dev"
set "IMAGE=neo4j:5.26.0"
set "DATA_VOLUME=atmkg-neo4j-dev-data"
set "LOGS_VOLUME=atmkg-neo4j-dev-logs"

if "%ATMKG_NEO4J_URI%"=="" goto missing_env
if "%ATMKG_NEO4J_DATABASE%"=="" goto missing_env
if "%ATMKG_NEO4J_USERNAME%"=="" goto missing_env
if "%ATMKG_NEO4J_PASSWORD%"=="" goto missing_env

if /I "%~1"=="start" goto start
if /I "%~1"=="stop" goto stop
if /I "%~1"=="status" goto status
if /I "%~1"=="reset" goto reset
goto usage

:start
docker ps --format "{{.Names}}" | findstr /X /C:"%CONTAINER%" >nul
if not errorlevel 1 (
    echo Neo4j dev container already running: %CONTAINER%
    exit /b 0
)
docker ps -a --format "{{.Names}}" | findstr /X /C:"%CONTAINER%" >nul
if not errorlevel 1 (
    docker start %CONTAINER% >nul
    goto status
)
docker volume create %DATA_VOLUME% >nul
docker volume create %LOGS_VOLUME% >nul
docker run -d --name %CONTAINER% --restart no -p 17474:7474 -p 17687:7687 -e "NEO4J_AUTH=%ATMKG_NEO4J_USERNAME%/%ATMKG_NEO4J_PASSWORD%" -v %DATA_VOLUME%:/data -v %LOGS_VOLUME%:/logs %IMAGE%
if errorlevel 1 exit /b 1
goto status

:stop
docker stop %CONTAINER%
exit /b %ERRORLEVEL%

:status
docker ps -a --filter "name=^/%CONTAINER%$" --format "container={{.Names}} status={{.Status}}"
echo Browser: http://127.0.0.1:17474
echo Bolt: %ATMKG_NEO4J_URI%
echo Database: %ATMKG_NEO4J_DATABASE%
echo Username: %ATMKG_NEO4J_USERNAME%
echo Password: supplied by ATMKG_NEO4J_PASSWORD (not printed)
exit /b 0

:reset
docker rm -f %CONTAINER% >nul 2>nul
docker volume rm %DATA_VOLUME% >nul 2>nul
docker volume rm %LOGS_VOLUME% >nul 2>nul
echo Neo4j dev container and disposable volumes removed.
exit /b 0

:missing_env
echo ERROR: set ATMKG_NEO4J_URI, ATMKG_NEO4J_DATABASE, ATMKG_NEO4J_USERNAME and ATMKG_NEO4J_PASSWORD first.
exit /b 2

:usage
echo Usage: neo4j-dev.cmd start^|stop^|status^|reset
exit /b 2
