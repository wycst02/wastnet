@echo off
rem ============================================================
rem  wastnet dual-JDK publish to Maven Central
rem  Step 1: JDK8  deploy JDK8 modules (core/common/javax/starter-javax)
rem  Step 2: JDK17 deploy jakarta modules (servlet-jakarta/starter-jakarta)
rem  Output printed directly to the current console.
rem ============================================================

set "JDK8_HOME=D:\Program Files\Java\jdk1.8.0_45"
set "JDK17_HOME=D:\java\jdk-17.0.3.1"
set "SKIP=-DskipTests"

setlocal
cd /d "%~dp0"

if not exist "%JDK8_HOME%\bin\javac.exe" goto :jdk8err
if not exist "%JDK17_HOME%\bin\javac.exe" goto :jdk17err

echo.
echo ====== Step 1: JDK8 deploy JDK8 modules (sources + javadoc + gpg) ======
set "JAVA_HOME=%JDK8_HOME%"
call mvn -P release clean deploy -pl wastnet-core,wastnet-servlet/wastnet-servlet-common,wastnet-servlet/wastnet-servlet-javax,wastnet-servlet/wastnet-spring-boot-starter-javax %SKIP%
if errorlevel 1 goto :step1err
echo.
echo [OK] Step 1 JDK8 deploy done.

echo.
echo ====== Step 2: JDK17 deploy jakarta modules (sources + javadoc + gpg) ======
set "JAVA_HOME=%JDK17_HOME%"
call mvn -P release clean deploy -pl wastnet-servlet/wastnet-servlet-jakarta,wastnet-servlet/wastnet-spring-boot-starter-jakarta %SKIP%
if errorlevel 1 goto :step2err
echo.
echo [OK] Step 2 JDK17 deploy done.

echo.
echo ====== ALL DEPLOY OK ======
endlocal
exit /b 0

:jdk8err
echo [ERROR] JDK8 not found: "%JDK8_HOME%"
exit /b 1

:jdk17err
echo [ERROR] JDK17 not found: "%JDK17_HOME%"
exit /b 1

:step1err
echo [ERROR] Step 1 JDK8 deploy failed.
exit /b 1

:step2err
echo [ERROR] Step 2 JDK17 deploy failed.
exit /b 1
