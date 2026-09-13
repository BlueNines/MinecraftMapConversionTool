<#
.SYNOPSIS
    构建地图降级转换工具，产出一个可以直接拷走运行的 dist/ 目录。

.DESCRIPTION
    把原本要手工做的几步一次做完：
      1. 编译主程序（gradlew :cli:shadowJar）
      2. 取回两个版本的 BlueMap（本地已有则直接用）
      3. 用 jlink 裁出一份自带 Java 运行时（约 63 MB，目标机器不需要装 Java）
    4. 组装 dist/，并写进启动脚本与说明

    全程幂等：重复运行会直接覆盖上次的产物。

    仓库根目录的 启动.bat 会直接跑这里的产物。如果要把成品拿给别人，
    把整个 dist/ 目录拷走即可（里面有它自己的启动脚本）。

.PARAMETER JavaHome
    JDK 21 的目录。不传则依次尝试：环境变量 JAVA_HOME、常见的 Temurin 安装位置。
    必须是 JDK（带 jlink），JRE 不行。

.PARAMETER SkipRuntime
    不重建 jlink 运行时。改 Java 代码时加它会快很多，因为运行时构建最慢。

.EXAMPLE
    .\tools\build.ps1

.EXAMPLE
    .\tools\build.ps1 -SkipRuntime
#>
[CmdletBinding()]
param(
    [string] $JavaHome,
    [switch] $SkipRuntime
)

$ErrorActionPreference = 'Stop'

# 脚本在 tools/ 下，仓库根在上一层
$root = Split-Path -Parent $PSScriptRoot
$chunker = Join-Path $root 'app\chunker'
$dist = Join-Path $root 'dist'
$toolsBin = Join-Path $PSScriptRoot 'bin'

function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Ok($text)   { Write-Host "  $text" -ForegroundColor Green }
function Info($text) { Write-Host "  $text" -ForegroundColor Gray }

# ------------------------------------------------------------
# 1. 找 JDK
# ------------------------------------------------------------
Step '找一个可用的 JDK'

if (-not $JavaHome) {
    $candidates = @($env:JAVA_HOME) + @(
        'C:\Users\28315\AppData\Local\Programs\Java\temurin-21',
        "$env:LOCALAPPDATA\Programs\Java\temurin-21",
        'C:\Program Files\Eclipse Adoptium\jdk-21',
        'C:\Program Files\Java\jdk-21'
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\jlink.exe'))) { $JavaHome = $c; break }
    }
}
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jlink.exe'))) {
    throw "找不到带 jlink 的 JDK 21。请用 -JavaHome 指定，例如：`n  .\tools\build.ps1 -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-21'"
}
Ok "JDK: $JavaHome"
$env:JAVA_HOME = $JavaHome

# ------------------------------------------------------------
# 2. 编译主程序
# ------------------------------------------------------------
Step '编译主程序（gradlew :cli:shadowJar）'
Push-Location $chunker
try {
    & '.\gradlew.bat' ':cli:shadowJar' '--no-daemon' 2>&1 |
        Select-String 'BUILD|error:|FAILURE' | ForEach-Object { Info $_.Line }
    if ($LASTEXITCODE -ne 0) { throw '编译失败，上面有原因。' }
} finally { Pop-Location }

$jarSource = Join-Path $chunker 'build\libs\chunker-cli-1.20.0.jar'
if (-not (Test-Path $jarSource)) { throw "编译完了但找不到产物：$jarSource" }
Ok "产物: $([math]::Round((Get-Item $jarSource).Length / 1MB, 2)) MB"

# ------------------------------------------------------------
# 3. 取回两个版本的 BlueMap
# ------------------------------------------------------------
Step '取回 BlueMap（两个版本）'

# 版本不能随便换：左边要能读现代存档，右边要能读 1.12.2。
# 没有任何一个版本能同时做到，而支持 1.12.2 的最后一个版本就是 1.5.5，再高就删了。
$blueMaps = @(
    @{ Name = 'BlueMap-5.16-cli.jar';  Url = 'https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v5.16/BlueMap-5.16-cli.jar' },
    @{ Name = 'BlueMap-1.5.5-cli.jar'; Url = 'https://github.com/BlueMap-Minecraft/BlueMap/releases/download/v1.5.5/BlueMap-1.5.5-cli.jar' }
)

New-Item -ItemType Directory -Force -Path $toolsBin | Out-Null
foreach ($bm in $blueMaps) {
    $target = Join-Path $toolsBin $bm.Name
    if (Test-Path $target) {
        Ok "$($bm.Name) 已存在，跳过下载"
        continue
    }
    Info "下载 $($bm.Name) …"
    try {
        Invoke-WebRequest -Uri $bm.Url -OutFile $target -UseBasicParsing -TimeoutSec 300
        Ok "$($bm.Name)  $([math]::Round((Get-Item $target).Length / 1MB, 2)) MB"
    } catch {
        throw "下载 $($bm.Name) 失败：$($_.Exception.Message)`n可以手动下载后放到 tools/bin/ 下再重跑（文件名必须一致）。`n地址：$($bm.Url)"
    }
}

# ------------------------------------------------------------
# 4. 组装 dist
# ------------------------------------------------------------
Step '组装 dist'

if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $dist 'bluemap') | Out-Null

Copy-Item $jarSource (Join-Path $dist 'chunker-cli-1.20.0.jar')
foreach ($bm in $blueMaps) {
    Copy-Item (Join-Path $toolsBin $bm.Name) (Join-Path $dist "bluemap\$($bm.Name)")
}
Ok '主程序与 BlueMap 已就位'

# ------------------------------------------------------------
# 5. jlink 运行时
# ------------------------------------------------------------
# 模块清单不是拍脑袋来的，是一路报错补出来的：
#   jdk.crypto.ec  → BlueMap 从 Mojang 下载贴图时 TLS 握手需要
#   java.sql       → BlueMap 的 Gson 依赖 java.sql.Time
#   java.desktop   → 选择地图文件夹要弹 Swing 的文件选择框
# 少任何一个都会在运行时才报错（而且往往是转换到一半）。
$modules = @(
    'java.base', 'java.compiler', 'java.datatransfer', 'java.desktop',
    'java.instrument', 'java.logging', 'java.management', 'java.naming',
    'java.net.http', 'java.prefs', 'java.rmi', 'java.scripting',
    'java.security.jgss', 'java.security.sasl', 'java.sql', 'java.sql.rowset',
    'java.transaction.xa', 'java.xml', 'jdk.charsets', 'jdk.crypto.cryptoki',
    'jdk.crypto.ec', 'jdk.dynalink', 'jdk.httpserver', 'jdk.jfr',
    'jdk.localedata', 'jdk.management', 'jdk.unsupported', 'jdk.zipfs'
)

$runtime = Join-Path $dist 'runtime'
if ($SkipRuntime -and (Test-Path $runtime)) {
    Ok '跳过运行时构建（-SkipRuntime，且 runtime 已存在）'
} else {
    Info '用 jlink 裁剪运行时…（最慢的一步，约半分钟）'
    if (Test-Path $runtime) { Remove-Item $runtime -Recurse -Force }
    & (Join-Path $JavaHome 'bin\jlink.exe') `
        --add-modules ($modules -join ',') `
        --output $runtime `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6
    if ($LASTEXITCODE -ne 0) { throw 'jlink 失败。' }
    Ok "运行时: $([math]::Round((Get-ChildItem $runtime -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB, 1)) MB"
}

# ------------------------------------------------------------
# 6. 启动脚本与说明
# ------------------------------------------------------------
Step '写启动脚本与说明'

# 启动脚本必须用 GBK(936) 存。cmd.exe 是按系统码页逐字节读脚本的，
# 用 UTF-8 存中文会变乱码，连换行都会被吃掉，一运行就是一堆莫名其妙的报错。
$bat = @'
@echo off
cd /d "%~dp0"
echo 地图降级转换工具
echo.
echo 正在启动，浏览器会自动打开界面...
echo 使用完毕后关闭本窗口即可退出。
echo.
"%~dp0runtime\bin\java.exe" -Dfile.encoding=UTF-8 -jar "%~dp0chunker-cli-1.20.0.jar"
if errorlevel 1 pause
'@
# 换行显式转成 CRLF：here-string 里的换行跟脚本自身检出的行尾一致，
# 而这是生成给 cmd.exe 读的脚本，不该受构建机行尾设置影响。
# （README 同理，否则记事本打开会是一整行。）
$bat = $bat -replace "`r?`n", "`r`n"
[IO.File]::WriteAllText((Join-Path $dist '启动.bat'), $bat, [Text.Encoding]::GetEncoding(936))
Ok '启动.bat（GBK 编码，CRLF）'

# README 用带 BOM 的 UTF-8：记事本等工具靠 BOM 判断编码，没有 BOM 会显示成乱码。
# 换行同样统一成 CRLF，否则记事本打开会是一整行。
$readmeSource = Join-Path $root 'docs\README-dist.md'
if (Test-Path $readmeSource) {
    $text = [IO.File]::ReadAllText($readmeSource) -replace "`r?`n", "`r`n"
    [IO.File]::WriteAllText((Join-Path $dist 'README.md'), $text, [Text.UTF8Encoding]::new($true))
    Ok 'README.md（带 BOM，CRLF）'
} else {
    Info '没有 docs/README-dist.md，跳过'
}

# ------------------------------------------------------------
Step '完成'
$files = (Get-ChildItem $dist -Recurse -File | Measure-Object -Property Length -Sum)
Ok "dist/ : $($files.Count) 个文件, $([math]::Round($files.Sum / 1MB, 1)) MB"
Info '仓库根的 启动.bat 已经能用了（双击即可）。'
Info '对外分发时拷走整个 dist/ 目录，里面有它自己的启动脚本。'
