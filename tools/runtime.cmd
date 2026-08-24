@echo off
setlocal EnableExtensions
chcp 65001 >nul

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if exist "%PROJECT_ROOT%\tools\env.cmd" call "%PROJECT_ROOT%\tools\env.cmd"

set "JAR=%PROJECT_ROOT%\target\atm-knowledge-graph-1.0-SNAPSHOT.jar"
set "LIB=%PROJECT_ROOT%\target\lib"
set "VIEWER=%PROJECT_ROOT%\viewer\dist\index.html"
set "PID_FILE=%PROJECT_ROOT%\runtime\state\service.pid"
set "LOG_DIR=%PROJECT_ROOT%\runtime\logs"
set "LOG_FILE=%LOG_DIR%\service.log"
set "ERROR_LOG_FILE=%LOG_DIR%\service-error.log"
if not defined ATMKG_API_HOST set "ATMKG_API_HOST=127.0.0.1"
if not defined ATMKG_API_PORT set "ATMKG_API_PORT=18080"

if /I "%~1"=="start" goto start
if /I "%~1"=="stop" goto stop
if /I "%~1"=="status" goto status
goto usage

:start
call :resolve_java
if errorlevel 1 exit /b 2
call :require_runtime_files
if errorlevel 1 exit /b 2
call :read_pid
if defined RUNNING_PID (
    echo ATMKG runtime already running: PID %RUNNING_PID%
    exit /b 0
)
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%" >nul 2>nul
if not exist "%PROJECT_ROOT%\runtime\state" mkdir "%PROJECT_ROOT%\runtime\state" >nul 2>nul
set "JAVA_ARGS=-cp "%JAR%;%LIB%\*" org.atmkg.tools.KgServiceMain "%PROJECT_ROOT%""
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p=Start-Process -FilePath $env:ATMKG_RUNTIME_JAVA -ArgumentList $env:ATMKG_RUNTIME_ARGS -WorkingDirectory $env:ATMKG_RUNTIME_ROOT -RedirectStandardOutput $env:ATMKG_RUNTIME_LOG -RedirectStandardError $env:ATMKG_RUNTIME_ERROR_LOG -WindowStyle Hidden -PassThru; Set-Content -LiteralPath $env:ATMKG_RUNTIME_PID -Value $p.Id -Encoding ascii"
if errorlevel 1 (
    echo ERROR: failed to start KgServiceMain. See %LOG_FILE%
    exit /b 1
)
ping 127.0.0.1 -n 4 >nul
call :read_pid
if not defined RUNNING_PID (
    echo ERROR: KgServiceMain exited during startup. See %LOG_FILE% and %ERROR_LOG_FILE%
    if exist "%PID_FILE%" del /q "%PID_FILE%" >nul 2>nul
    exit /b 1
)
echo ATMKG runtime started: PID %RUNNING_PID%
echo Log: %LOG_FILE%
echo Health: http://%ATMKG_API_HOST%:%ATMKG_API_PORT%/api/v1/health
exit /b 0

:stop
call :read_pid
if not defined RUNNING_PID (
    echo ATMKG runtime is not running; no PID file found.
    if exist "%PID_FILE%" del /q "%PID_FILE%" >nul 2>nul
    exit /b 0
)
taskkill /PID %RUNNING_PID% /T /F >nul 2>nul
if errorlevel 1 (
    tasklist /FI "PID eq %RUNNING_PID%" | findstr /R /C:" %RUNNING_PID% " >nul
    if errorlevel 1 (
        del /q "%PID_FILE%" >nul 2>nul
        echo ATMKG runtime was already stopped; removed stale PID file.
        exit /b 0
    )
    echo WARNING: PID %RUNNING_PID% could not be stopped; inspect tasklist manually.
    exit /b 1
)
del /q "%PID_FILE%" >nul 2>nul
echo ATMKG runtime stopped: PID %RUNNING_PID%
exit /b 0

:status
call :read_pid
if defined RUNNING_PID (
    echo ATMKG runtime running: PID %RUNNING_PID%
) else (
    echo ATMKG runtime stopped
)
echo Health: http://%ATMKG_API_HOST%:%ATMKG_API_PORT%/api/v1/health
exit /b 0

:read_pid
set "RUNNING_PID="
if not exist "%PID_FILE%" exit /b 0
set /p "CANDIDATE="<"%PID_FILE%"
if not defined CANDIDATE exit /b 0
tasklist /FI "PID eq %CANDIDATE%" | findstr /R /C:" %CANDIDATE% " >nul
if not errorlevel 1 set "RUNNING_PID=%CANDIDATE%"
exit /b 0

:resolve_java
set "JAVA_COMMAND="
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set. Formal runtime requires the private JDK configured in tools\env.cmd.
    exit /b 1
)
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_COMMAND if defined JAVA_HOME if exist "%JAVA_HOME%\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\java.exe"
if not defined JAVA_COMMAND (
    echo ERROR: Java 17 not found. Set JAVA_HOME in tools\env.cmd to the JDK root or its bin directory.
    exit /b 1
)
set "ATMKG_RUNTIME_JAVA=%JAVA_COMMAND%"
set "ATMKG_RUNTIME_ARGS=-cp "%JAR%;%LIB%\*" org.atmkg.tools.KgServiceMain "%PROJECT_ROOT%""
set "ATMKG_RUNTIME_ROOT=%PROJECT_ROOT%"
set "ATMKG_RUNTIME_LOG=%LOG_FILE%"
set "ATMKG_RUNTIME_ERROR_LOG=%ERROR_LOG_FILE%"
set "ATMKG_RUNTIME_PID=%PID_FILE%"
exit /b 0

:require_runtime_files
for %%V in (ATMKG_NEO4J_URI ATMKG_NEO4J_DATABASE ATMKG_NEO4J_USERNAME ATMKG_NEO4J_PASSWORD) do if not defined %%V (
    echo ERROR: %%V is missing. Copy tools\env.cmd.example to tools\env.cmd and fill it in.
    exit /b 1
)
if not exist "%JAR%" (
    echo ERROR: Java artifact missing: %JAR%
    echo Run Maven once: mvn.cmd -DskipTests package
    exit /b 1
)
if not exist "%LIB%\*.jar" (
    echo ERROR: runtime dependency directory is missing or empty: %LIB%
    echo Run Maven once: mvn.cmd -DskipTests package
    exit /b 1
)
if not exist "%VIEWER%" echo WARNING: Viewer dist is missing: %VIEWER%
for %%F in ("%PROJECT_ROOT%\config\api.yaml" "%PROJECT_ROOT%\config\sources.yaml" "%PROJECT_ROOT%\mapping\字段映射.xlsx" "%PROJECT_ROOT%\ontology\atm_knowledge_graph.ttl") do if not exist "%%~F" (
    echo ERROR: required project file is missing: %%~F
    exit /b 1
)
exit /b 0

:usage
echo Usage: tools\runtime.cmd start^|stop^|status
echo Formal offline runtime: uses target jar/lib and viewer\dist; does not invoke Maven or npm.
exit /b 2
