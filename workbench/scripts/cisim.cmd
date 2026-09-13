@echo off
rem Simulate the CI environment locally: cli/data (607 MB of per-version data
rem tables) is not in the repository, so hide it and force the test task to
rem actually run -- without --rerun-tasks Gradle reports UP-TO-DATE and skips
rem the tests entirely, which looks like a pass but proves nothing.
rem
rem Expects to be run from the repository root:  workbench\scripts\cisim.cmd
setlocal

if not exist "app\chunker\gradlew.bat" (
  echo Run this from the repository root.
  exit /b 2
)

set LOG=%TEMP%\cisim.log
echo [start] > "%LOG%"

cd app\chunker

set RESTORE=
if exist cli\data (
  ren cli\data data__ci_sim
  set RESTORE=1
  echo [moved cli/data away, simulating a fresh clone] >> "%LOG%"
) else (
  echo [cli/data already absent - was it lost rather than moved?] >> "%LOG%"
)

call .\gradlew.bat :cli:test --no-daemon --rerun-tasks >> "%LOG%" 2>&1
echo [gradle exit=%ERRORLEVEL%] >> "%LOG%"

rem Restore from the same directory the rename happened in. An earlier version
rem tried to restore through a longer relative path, failed silently, and left
rem the 607 MB of data sitting under the wrong name.
if defined RESTORE (
  if exist cli\data__ci_sim (
    ren cli\data__ci_sim data
    echo [restored cli/data] >> "%LOG%"
  ) else (
    echo [WARNING] cli\data__ci_sim is missing, nothing to restore >> "%LOG%"
  )
)

cd ..\..
echo [done] >> "%LOG%"
echo Log: %LOG%
