@echo off
REM Start all 4 benchmark servers (wastnet: 8080/8443, undertow: 8081/8444) in separate windows.
REM Usage: double-click or run "bench-start.bat" from the project root.

setlocal enabledelayedexpansion

cd /d "%~dp0"

REM Locate the fat-jar (skip the 'original-' shaded jar), newest first.
set JAR=
for /f "delims=" %%f in ('dir /b /o-d wastnet-test\target\wastnet-test-*.jar 2^>nul') do (
    echo %%f | findstr /i "original-" >nul || (
        if not defined JAR set "JAR=%~dp0wastnet-test\target\%%f"
    )
)

if not defined JAR (
    echo [bench-start] fat-jar not found, building...
    call mvn -pl wastnet-test -am package -DskipTests -q
    for /f "delims=" %%f in ('dir /b /o-d wastnet-test\target\wastnet-test-*.jar 2^>nul') do (
        echo %%f | findstr /i "original-" >nul || (
            if not defined JAR set "JAR=%~dp0wastnet-test\target\%%f"
        )
    )
)

if not defined JAR (
    echo [bench-start] ERROR: could not find or build wastnet-test jar >&2
    pause
    exit /b 1
)

set VM=-Xms256m -Xmx256m -Dwastnet.http.pipeline.enabled=true
set MAIN=io.github.wycst.wastnet.benchmarks.http.BenchmarkLauncher

REM Use the bundled JDK 21 so h2 (TLS/ALPN) works; override with JAVA_HOME env if needed.
if not defined JAVA_HOME set "JAVA_HOME=D:\java\jdk-21"
set "JAVA=%JAVA_HOME%\bin\java.exe"

echo [bench-start] jar=%JAR%
echo [bench-start] java=%JAVA%

REM Pre-build the full command line (variables expanded here in the main process),
REM then launch each in its own window via cmd /k. The JAR path has no spaces, so
REM no inner quotes are needed (inner quotes inside cmd /k would break %VAR% expansion).
set CMD_W1=%JAVA% %VM% -Dimpl=wastnet  -Dproto=h1  -Dport=8080 -cp %JAR% %MAIN%
set CMD_W2=%JAVA% %VM% -Dimpl=wastnet  -Dproto=h2  -Dport=8443 -cp %JAR% %MAIN%
set CMD_W3=%JAVA% %VM% -Dimpl=wastnet  -Dproto=h2c -Dport=8445 -cp %JAR% %MAIN%
set CMD_U1=%JAVA% %VM% -Dimpl=undertow -Dproto=h1p -Dport=8081 -cp %JAR% %MAIN%
set CMD_U2=%JAVA% %VM% -Dimpl=undertow -Dproto=h2  -Dport=8444 -cp %JAR% %MAIN%
set CMD_U3=%JAVA% %VM% -Dimpl=undertow -Dproto=h2c -Dport=8446 -cp %JAR% %MAIN%

start "wastnet-h1"   cmd /k "%CMD_W1% & echo [exit] wastnet-h1 & pause"
start "wastnet-h2"   cmd /k "%CMD_W2% & echo [exit] wastnet-h2 & pause"
start "wastnet-h2c"  cmd /k "%CMD_W3% & echo [exit] wastnet-h2c & pause"
start "undertow-h1"  cmd /k "%CMD_U1% & echo [exit] undertow-h1 & pause"
start "undertow-h2"  cmd /k "%CMD_U2% & echo [exit] undertow-h2 & pause"
start "undertow-h2c" cmd /k "%CMD_U3% & echo [exit] undertow-h2c & pause"

echo [bench-start] started 6 servers in separate windows.
echo [bench-start] close each window to stop that server.
endlocal
