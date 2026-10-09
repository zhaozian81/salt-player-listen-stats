# 从本机已安装的 Salt Player for Windows 里抽取 Workshop API 类，做成编译桩。
#
#   powershell -ExecutionPolicy Bypass -File .\extract-host-api.ps1
#   powershell -ExecutionPolicy Bypass -File .\extract-host-api.ps1 -SpwDir "D:\steam\st\steamapps\common\Salt Player for Windows"
#
# 产物：<仓库>/build/host-api/spw-workshop-api-legacy.jar（已被 .gitignore 忽略，不要提交）
#
# 为什么要这么做：宿主把整个程序打成了伪装成 ffmpeg-x64.dll 的 fat jar，
# 旧宿主（如 1.18.5）的 API 比 JitPack 上的新版少了若干成员，
# 用这份真实 API 编译出来的插件才是"旧宿主一定能加载"的那一种。

[CmdletBinding()]
param(
    [string] $SpwDir = ''
)

$ErrorActionPreference = 'Stop'

function Find-SpwDir {
    $candidates = @(
        'C:\Program Files (x86)\Steam\steamapps\common\Salt Player for Windows',
        'C:\Program Files\Steam\steamapps\common\Salt Player for Windows',
        'D:\Steam\steamapps\common\Salt Player for Windows',
        'D:\SteamLibrary\steamapps\common\Salt Player for Windows',
        'E:\Steam\steamapps\common\Salt Player for Windows',
        'E:\SteamLibrary\steamapps\common\Salt Player for Windows'
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c 'app\ffmpeg-x64.dll')) { return $c }
    }
    return $null
}

if (-not $SpwDir) { $SpwDir = Find-SpwDir }
if (-not $SpwDir) {
    throw "找不到 Salt Player 安装目录，请用 -SpwDir 指定（目录里应有 app\ffmpeg-x64.dll）"
}

$dll = Join-Path $SpwDir 'app\ffmpeg-x64.dll'
if (-not (Test-Path $dll)) { throw "找不到 $dll" }

# --- 找 JDK（只要 jar.exe） -------------------------------------------------
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jdkCandidates += (Get-ChildItem "$env:LOCALAPPDATA\dsh-tools" -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like 'jdk*' } | ForEach-Object { $_.FullName })
$jarExit = (Get-Command jar.exe -ErrorAction SilentlyContinue).Source
if ($jarExit) { $jdkCandidates += (Split-Path (Split-Path $jarExit)) }

$jar = $null
foreach ($c in $jdkCandidates) {
    if ($c -and (Test-Path (Join-Path $c 'bin\jar.exe'))) { $jar = Join-Path $c 'bin\jar.exe'; break }
}
if (-not $jar) { throw '找不到 jar.exe，请设置 JAVA_HOME 或把 JDK 的 bin 加入 PATH' }

# --- 抽取 ------------------------------------------------------------------
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outDir = Join-Path $repoRoot 'build\host-api'
$tmp = Join-Path $outDir 'unpacked'
if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
New-Item -ItemType Directory -Force $tmp | Out-Null

Write-Host "宿主: $dll"
Write-Host '解包 API 类…'
& tar.exe -xf $dll -C $tmp 'com/xuncorp/spw/workshop/api/*'
if ($LASTEXITCODE -ne 0) { throw 'tar 解包失败' }

$jarOut = Join-Path $outDir 'spw-workshop-api-legacy.jar'
& $jar cf $jarOut -C $tmp com
if ($LASTEXITCODE -ne 0) { throw '打包失败' }

$count = (& $jar tf $jarOut | Measure-Object).Count
Write-Host "完成: $jarOut（$count 个条目）"
if ($count -lt 20) { throw "条目数异常（$count），抽取可能不完整" }
