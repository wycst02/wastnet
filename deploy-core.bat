@echo off
rem ============================================================
rem  wastnet publish core only to Maven Central (JDK8)
rem  mvn -P release clean deploy -pl wastnet-core
rem  Output printed directly to the current console.
rem ============================================================

set "JDK8_HOME=D:\Program Files\Java\jdk1.8.0_45"
set "SKIP=-DskipTests"

setlocal
cd /d "%~dp0"

if not exist "%JDK8_HOME%\bin\javac.exe" goto :jdk8err

echo.
echo ====== Deploy core only (JDK8, sources + javadoc + gpg) ======
set "JAVA_HOME=%JDK8_HOME%"
call mvn -P release clean deploy -pl wastnet-core %SKIP%
if errorlevel 1 goto :deployerr
echo.
echo [OK] core deploy done.
echo ====== CORE DEPLOY OK ======
endlocal
exit /b 0

:jdk8err
echo [ERROR] JDK8 not found: "%JDK8_HOME%"
exit /b 1

:deployerr
echo [ERROR] core deploy failed.
exit /b 1
