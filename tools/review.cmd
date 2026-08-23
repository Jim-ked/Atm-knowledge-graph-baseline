@echo off
setlocal
chcp 65001 >nul

for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
set "MAVEN_COMMAND=mvn.cmd"
set "JAVA_COMMAND=java.exe"

if not "%MAVEN_HOME%"=="" set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
if not "%JAVA_HOME%"=="" set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"

if not exist "%MAVEN_COMMAND%" where %MAVEN_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_maven
if not exist "%JAVA_COMMAND%" where %JAVA_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_java

pushd "%PROJECT_ROOT%"
call "%MAVEN_COMMAND%" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.review.ReviewTool"
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:missing_maven
echo ERROR: Maven was not found. Put mvn.cmd on PATH or set MAVEN_HOME.
exit /b 2

:missing_java
echo ERROR: Java was not found. Put java.exe on PATH or set JAVA_HOME.
exit /b 2
