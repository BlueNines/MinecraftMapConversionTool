@echo off
rem Reproduce the smoke job from .github/workflows/ci.yml locally.
rem Only the platform differs (Windows here, ubuntu there) - the commands and
rem the assertions are the same. Run it from the repository root:
rem   workbench\scripts\cismoke.cmd
rem
rem It caught three things the workflow would have failed on:
rem   1. locating the jar recursively picked up a stale jpackage copy in
rem      libs/packaged/ that predates --shiftToFit
rem   2. the jar needs the `cli` argument, or it starts the web interface and
rem      blocks forever
rem   3. the sample world zips hold level.dat at their root, not in a folder
setlocal

if not exist "app\chunker\gradlew.bat" (
  echo Run this from the repository root.
  exit /b 2
)

rem The tool needs Java 21 and the default java on this machine is 1.8, so do
rem not trust whatever `java` resolves to on the PATH.
set JAVA=java
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set JAVA=%JAVA_HOME%\bin\java.exe
if /i "%JAVA%"=="java" if exist "%LOCALAPPDATA%\Programs\Java\temurin-21\bin\java.exe" set JAVA=%LOCALAPPDATA%\Programs\Java\temurin-21\bin\java.exe

set REPO=%CD%
set T=%TEMP%\ci-smoke
set LOG=%TEMP%\ci-smoke.log
echo [start] > "%LOG%"
echo [java] %JAVA% >> "%LOG%"

rem Locate the shaded jar the same way the workflow does: top level only,
rem and not the -unshaded build.
set JAR=
for %%f in ("%REPO%\app\chunker\cli\build\libs\chunker-cli-*.jar") do (
  echo %%~nxf | findstr /i /c:"-unshaded" >nul || set JAR=%%f
)
if "%JAR%"=="" (
  echo [FAIL] no packaged jar found - run tools\build.bat first >> "%LOG%"
  goto :done
)
echo [jar] %JAR% >> "%LOG%"

for %%W in (JAVA_1_15_2 JAVA_1_20_5) do (
  echo === %%W === >> "%LOG%"
  if exist "%T%" rmdir /s /q "%T%"
  mkdir "%T%\world"
  powershell -NoProfile -Command "Expand-Archive -LiteralPath '%REPO%\app\chunker\cli\src\test\resources\integration\worlds\%%W.zip' -DestinationPath '%T%\world' -Force" >> "%LOG%" 2>&1
  if not exist "%T%\world\level.dat" (
    echo [FAIL] level.dat not at root for %%W >> "%LOG%"
    goto :done
  )

  rem cli comes first: without it the jar starts the web interface and blocks forever.
  "%JAVA%" -Xmx3G -jar "%JAR%" cli --inputDirectory "%T%\world" --outputFormat JAVA_1_12_2 --outputDirectory "%T%\out" --shiftToFit >> "%LOG%" 2>&1
  echo [convert %%W exit=%ERRORLEVEL%] >> "%LOG%"

  py "%REPO%\tools\ci\smoke_check.py" "%T%\out" >> "%LOG%" 2>&1
  echo [check %%W exit=%ERRORLEVEL%] >> "%LOG%"
)

:done
echo [done] >> "%LOG%"
echo Log: %LOG%
