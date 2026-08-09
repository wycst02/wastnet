@echo off
rem ============================================================
rem  wastnet dual-JDK local install (no publish)
rem  Step 1: JDK8  clean install JDK8 modules (core/common/javax/starter-javax)
rem  Step 2: JDK17 clean install jakarta modules (servlet-jakarta/starter-jakarta)
rem          NOTE: Step 2 is temporarily disabled (jakarta build commented out).
rem  Output printed directly to the current console.
rem ============================================================

set "JDK8_HOME=D:\Program Files\Java\jdk1.8.0_45"
set "JDK17_HOME=D:\java\jdk-17.0.3.1"
set "SKIP=-DskipTests"

setlocal
cd /d "%~dp0"

if not exist "%JDK8_HOME%\bin\javac.exe" goto :jdk8err
rem if not exist "%JDK17_HOME%\bin\javac.exe" goto :jdk17err

echo.
echo ====== Step 1: JDK8 clean install JDK8 modules (with sources, no gpg) ======
set "JAVA_HOME=%JDK8_HOME%"
call mvn -P release clean install -pl :wastnet-core,:wastnet-servlet,:wastnet-servlet-common,:wastnet-servlet-javax,:wastnet-spring-boot-starter-javax %SKIP% -Dgpg.skip=true
if errorlevel 1 goto :step1err
echo.
echo [OK] Step 1 JDK8 done.

rem ====== Step 2 (jakarta) temporarily disabled ======
rem Uncomment the block below to enable JDK17 jakarta module install again.
rem
rem Clean Maven resolver failure caches (*.lastUpdated) so Step 2 resolves the local
rem parent/child poms instead of re-fetching from the remote nexus repository.
rem if exist "%USERPROFILE%\.m2\repository\io\github\wycst" (
rem     del /s /q "%USERPROFILE%\.m2\repository\io\github\wycst\wastnet*\*.lastUpdated" >nul 2>&1
rem )
rem
rem echo.
rem echo ====== Step 2: JDK17 clean install jakarta modules (with sources, no gpg) ======
rem set "JAVA_HOME=%JDK17_HOME%"
rem call mvn -P release clean install -pl :wastnet-servlet-jakarta,:wastnet-spring-boot-starter-jakarta %SKIP% -Dgpg.skip=true
rem if errorlevel 1 goto :step2err
rem echo.
rem echo [OK] Step 2 JDK17 done.

echo.
echo ====== Step 3: package wastnet-test (no install/deploy, for benchmark) ======
rem wastnet-test has maven.install.skip/deploy.skip=true, so it is never published.
rem Here we only build the fat-jar into wastnet-test/target for local benchmarking.
set "JAVA_HOME=%JDK8_HOME%"
call mvn -pl wastnet-test -am package -DskipTests %SKIP% -Dgpg.skip=true
if errorlevel 1 goto :step3err
echo.
echo [OK] Step 3 wastnet-test packaged.

echo.
echo ====== ALL INSTALL OK ======
endlocal
exit /b 0

:step3err
echo [ERROR] Step 3 wastnet-test package failed.
exit /b 1

:jdk8err
echo [ERROR] JDK8 not found: "%JDK8_HOME%"
exit /b 1

:jdk17err
echo [ERROR] JDK17 not found: "%JDK17_HOME%"
exit /b 1

:step1err
echo [ERROR] Step 1 JDK8 install failed.
exit /b 1

:step2err
echo [ERROR] Step 2 JDK17 install failed.
exit /b 1
