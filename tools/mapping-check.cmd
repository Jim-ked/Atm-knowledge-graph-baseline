@echo off
setlocal
chcp 65001 >nul
for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
if exist "%PROJECT_ROOT%\tools\env.cmd" call "%PROJECT_ROOT%\tools\env.cmd"
set "MAVEN_COMMAND=mvn.cmd"
if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
pushd "%PROJECT_ROOT%"
call "%MAVEN_COMMAND%" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.MappingCheckMain" "-Dexec.args=%*"
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
