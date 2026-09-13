@echo off
rem 构建入口。存在的理由：Windows 默认禁止直接运行 .ps1 脚本
rem （ExecutionPolicy 默认是 Restricted），双击或直接调用会被拦下来。
rem 这里用 -ExecutionPolicy Bypass 只对本次调用生效，不改系统设置。
rem
rem 参数原样转给 build.ps1，例如：
rem   tools\build.bat -SyncTest
rem   tools\build.bat -SkipRuntime
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
exit /b %ERRORLEVEL%
