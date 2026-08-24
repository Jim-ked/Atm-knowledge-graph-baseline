@echo off
setlocal EnableExtensions
chcp 65001 >nul

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if exist "%PROJECT_ROOT%\tools\env.cmd" call "%PROJECT_ROOT%\tools\env.cmd"

echo ==== ATMKG handover check ====
echo Project root: %PROJECT_ROOT%

set "JAVA_COMMAND="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_COMMAND if defined JAVA_HOME if exist "%JAVA_HOME%\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\java.exe"
if not defined JAVA_COMMAND for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_COMMAND set "JAVA_COMMAND=%%J"
if not defined JAVA_HOME echo WARNING: JAVA_HOME is not set; PATH Java is a development fallback, not the formal private-JDK runtime.
if defined JAVA_COMMAND (
    echo Java: %JAVA_COMMAND%
    "%JAVA_COMMAND%" -version
) else echo ERROR: Java not found; set JAVA_HOME in tools\env.cmd.

set "MAVEN_COMMAND="
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MAVEN_COMMAND if defined MAVEN_HOME if exist "%MAVEN_HOME%\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\mvn.cmd"
if not defined MAVEN_COMMAND for /f "delims=" %%M in ('where mvn.cmd 2^>nul') do if not defined MAVEN_COMMAND set "MAVEN_COMMAND=%%M"
if defined MAVEN_COMMAND (echo Maven: %MAVEN_COMMAND%) else echo Maven: not configured (not required by formal runtime)

for %%V in (ATMKG_NEO4J_URI ATMKG_NEO4J_DATABASE ATMKG_NEO4J_USERNAME ATMKG_NEO4J_PASSWORD) do call :check_env %%V
if defined ATMKG_NEO4J_HOME (if exist "%ATMKG_NEO4J_HOME%\bin\neo4j.bat" (echo Neo4j home: %ATMKG_NEO4J_HOME%) else echo ERROR: Neo4j bin\neo4j.bat not found under ATMKG_NEO4J_HOME) else echo Neo4j home: not configured

for %%F in ("config\api.yaml" "config\sources.yaml" "mapping\字段映射.xlsx" "ontology\atm_knowledge_graph.ttl" "viewer\dist\index.html") do if exist "%PROJECT_ROOT%\%%~F" (echo OK: %%~F) else echo ERROR: missing %%~F
if exist "%PROJECT_ROOT%\target\atm-knowledge-graph-1.0-SNAPSHOT.jar" (echo OK: target Java artifact) else echo ERROR: target Java artifact missing; run mvn -DskipTests package once
if exist "%PROJECT_ROOT%\target\lib\*.jar" (echo OK: target runtime dependencies) else echo ERROR: target\lib is missing or empty; run mvn -DskipTests package once

echo.
echo Port check command: netstat -ano ^| findstr :18080
netstat -ano | findstr :18080
if errorlevel 1 echo Port 18080 has no matching listener.
if exist "%PROJECT_ROOT%\runtime\state\service.pid" echo Runtime PID file: runtime\state\service.pid
echo.
echo SLF4J/Log4j provider warnings are currently non-fatal startup warnings; inspect health and service log before treating them as failure.
exit /b 0

:check_env
if "%~1"=="ATMKG_NEO4J_PASSWORD" (
    if defined ATMKG_NEO4J_PASSWORD (echo OK: ATMKG_NEO4J_PASSWORD is set) else echo ERROR: ATMKG_NEO4J_PASSWORD is missing
) else (
    if defined %~1 (echo OK: %~1 is set) else echo ERROR: %~1 is missing
)
exit /b 0
