@echo off
setlocal
chcp 65001 >nul

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if exist "%PROJECT_ROOT%\tools\env.cmd" call "%PROJECT_ROOT%\tools\env.cmd"
set "MAVEN_COMMAND=mvn.cmd"
set "JAVA_COMMAND=java.exe"

if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\mvn.cmd"
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\java.exe"

if not exist "%MAVEN_COMMAND%" where %MAVEN_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_maven
if not exist "%JAVA_COMMAND%" where %JAVA_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_java

pushd "%PROJECT_ROOT%"
call "%MAVEN_COMMAND%" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.SourcePreviewMain" "-Dexec.args=%*"
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:usage
echo Usage: tools\source-preview.cmd ^<sources.yaml^> ^<sourceId^> ^<objectName^> [limit]
echo Example: tools\source-preview.cmd config\sources.local.yaml excel-main route-segment 5
echo JDBC: tools\source-preview.cmd config\sources.yaml jdbc-main example-object 5
exit /b 2

:missing_maven
echo ERROR: Maven was not found. Put mvn.cmd on PATH or set MAVEN_HOME.
exit /b 2

:missing_java
echo ERROR: Java was not found. Put java.exe on PATH or set JAVA_HOME.
exit /b 2
