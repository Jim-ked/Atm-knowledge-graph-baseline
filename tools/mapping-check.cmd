@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
for %%I in ("%~dp0..") do set "SCRIPT_ROOT=%%~fI"
if exist "%SCRIPT_ROOT%\tools\env.cmd" call "%SCRIPT_ROOT%\tools\env.cmd"
set "PROJECT_ROOT=%SCRIPT_ROOT%"
if not "%ATMKG_PROJECT_ROOT%"=="" if exist "%ATMKG_PROJECT_ROOT%\pom.xml" set "PROJECT_ROOT=%ATMKG_PROJECT_ROOT%"
set "JAVA_COMMAND="
if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_COMMAND if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\java.exe" set "JAVA_COMMAND=%JAVA_HOME%\java.exe"
if not defined JAVA_COMMAND for %%J in (java.exe) do set "JAVA_COMMAND=%%~$PATH:J"
pushd "%PROJECT_ROOT%"
if exist "target\atm-knowledge-graph-1.0-SNAPSHOT.jar" (
 if not defined JAVA_COMMAND (echo 未找到 Java。& popd& exit /b 2)
 set "CP=target\atm-knowledge-graph-1.0-SNAPSHOT.jar"
 if exist "target\lib" set "CP=%CP%;target\lib\*"
 "!JAVA_COMMAND!" -cp "!CP!" org.atmkg.tools.MappingCheckMain %*
) else (
 set "MAVEN_COMMAND="
 if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\bin\mvn.cmd"
 if not defined MAVEN_COMMAND if not "%MAVEN_HOME%"=="" if exist "%MAVEN_HOME%\mvn.cmd" set "MAVEN_COMMAND=%MAVEN_HOME%\mvn.cmd"
 if not defined MAVEN_COMMAND for %%M in (mvn.cmd) do set "MAVEN_COMMAND=%%~$PATH:M"
 if not defined MAVEN_COMMAND (echo 未找到已打包 runtime，也未找到 Maven。& popd& exit /b 2)
 call "!MAVEN_COMMAND!" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=org.atmkg.tools.MappingCheckMain" "-Dexec.args=%*"
)
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%
echo 未找到已打包 runtime，也未找到 Maven。
exit /b 2
