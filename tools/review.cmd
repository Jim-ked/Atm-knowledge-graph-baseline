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
if not exist "%JAVA_COMMAND%" where %JAVA_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_java
for %%V in (ATMKG_NEO4J_URI ATMKG_NEO4J_DATABASE ATMKG_NEO4J_USERNAME ATMKG_NEO4J_PASSWORD) do if not defined %%V goto missing_neo4j

if exist "%PROJECT_ROOT%\target\atm-knowledge-graph-1.0-SNAPSHOT.jar" if exist "%PROJECT_ROOT%\target\lib\*.jar" goto runtime_review

if not exist "%MAVEN_COMMAND%" where %MAVEN_COMMAND% >nul 2>nul
if errorlevel 1 goto missing_maven
pushd "%PROJECT_ROOT%"
call "%MAVEN_COMMAND%" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.review.ReviewTool"
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:runtime_review
pushd "%PROJECT_ROOT%"
call "%JAVA_COMMAND%" -cp "target\atm-knowledge-graph-1.0-SNAPSHOT.jar;target\lib\*" org.atmkg.tools.review.ReviewTool "%PROJECT_ROOT%"
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:missing_maven
echo ERROR: Maven was not found. Put mvn.cmd on PATH or set MAVEN_HOME.
exit /b 2

:missing_java
echo ERROR: Java was not found. Put java.exe on PATH or set JAVA_HOME.
exit /b 2

:missing_neo4j
echo ERROR: one or more ATMKG_NEO4J_* variables are missing. Fill tools\env.cmd first.
exit /b 2
