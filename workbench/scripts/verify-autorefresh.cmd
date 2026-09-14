@echo off
rem Verify that a conversion refreshes the result preview on its own.
rem The point: /api/render is never called here, so an afterPort appearing without it
rem is proof the backend now starts the preview after a conversion.
setlocal
set REPO=C:\Users\28315\Desktop\ai转换地图
set T=%TEMP%\auto6
set LOG=%TEMP%\auto6.log
set JAVA=%LOCALAPPDATA%\Programs\Java\temurin-21\bin\java.exe

echo [start] > "%LOG%"
if exist "%T%" rmdir /s /q "%T%"
mkdir "%T%\bluemap"
mkdir "%T%\world"
copy /y "%REPO%\app\chunker\cli\build\libs\chunker-cli-1.20.0.jar" "%T%\" >> "%LOG%" 2>&1
copy /y "%REPO%\tools\bin\BlueMap-5.16-cli.jar" "%T%\bluemap\" >> "%LOG%" 2>&1
copy /y "%REPO%\tools\bin\BlueMap-1.5.5-cli.jar" "%T%\bluemap\" >> "%LOG%" 2>&1
powershell -NoProfile -Command "Expand-Archive -LiteralPath '%REPO%\app\chunker\cli\src\test\resources\integration\worlds\JAVA_1_15_2.zip' -DestinationPath '%T%\world' -Force" >> "%LOG%" 2>&1

start "tool" /min "%JAVA%" -Dchunker.noBrowser=true -jar "%T%\chunker-cli-1.20.0.jar"
echo [waiting for server] >> "%LOG%"

set PORT=
for /l %%i in (1,1,20) do (
  if not defined PORT (
    powershell -NoProfile -Command "try{(Invoke-WebRequest 'http://localhost:8123/' -UseBasicParsing -TimeoutSec 2).StatusCode}catch{exit 1}" >nul 2>&1
    if not errorlevel 1 set PORT=8123
    if not defined PORT timeout /t 2 /nobreak >nul
  )
)
if not defined PORT (echo [FAIL] server never came up >> "%LOG%" & goto :done)
echo [port=%PORT%] >> "%LOG%"

> "%T%\c.json" echo {"input":"%T:\=\%\\world","output":"%T:\=\%\\out"}
powershell -NoProfile -Command "Invoke-WebRequest 'http://localhost:%PORT%/api/convert' -Method Post -InFile '%T%\c.json' -ContentType 'application/json; charset=utf-8' -UseBasicParsing -TimeoutSec 60 | Out-Null" >> "%LOG%" 2>&1
echo [convert posted - no /api/render call from here on] >> "%LOG%"

for /l %%i in (1,1,30) do (
  powershell -NoProfile -Command "$s=Invoke-RestMethod 'http://localhost:%PORT%/api/status' -TimeoutSec 5; Write-Output ('st='+$s.status+' render='+$s.renderStatus+' afterPort='+$s.afterPort+' rev='+$s.resultRevision+' changed='+$s.changedChunks)" >> "%LOG%" 2>&1
  timeout /t 12 /nobreak >nul
)

:done
taskkill /f /im java.exe >nul 2>&1
echo [done] >> "%LOG%"
