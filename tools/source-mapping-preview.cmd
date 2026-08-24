@echo off
setlocal
chcp 65001 >nul

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if exist "%PROJECT_ROOT%\tools\env.cmd" call "%PROJECT_ROOT%\tools\env.cmd"
set "MAVEN_COMMAND=mvn.cmd"
if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\mvn.cmd"
if not exist "%MAVEN_COMMAND%" where %MAVEN_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_maven

pushd "%PROJECT_ROOT%"
call "%MAVEN_COMMAND%" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.SourceMappingPreviewMain" "-Dexec.args=%*"
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:missing_maven
echo ERROR: Maven was not found. Put mvn.cmd on PATH or set MAVEN_HOME.
exit /b 2
