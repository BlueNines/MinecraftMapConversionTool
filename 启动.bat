@echo off
cd /d "%~dp0"

if not exist "dist\chunker-cli-1.20.0.jar" (
  echo.
  echo 还没有构建过，请先运行： tools\build.bat
  echo.
  pause
  exit /b 1
)

echo 地图降级转换工具
echo.
echo 正在启动，浏览器会自动打开界面...
echo 使用完毕后关闭本窗口即可退出。
echo.

if exist "dist\runtime\bin\java.exe" (
  "dist\runtime\bin\java.exe" -Dfile.encoding=UTF-8 -jar "dist\chunker-cli-1.20.0.jar"
) else (
  java -Dfile.encoding=UTF-8 -jar "dist\chunker-cli-1.20.0.jar"
)
if errorlevel 1 pause