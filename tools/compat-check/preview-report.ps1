# 用真实统计数据生成报表预览（不启动播放器）。
#
#   powershell -ExecutionPolicy Bypass -File .\preview-report.ps1 `
#       -PluginZip ..\..\build\libs\plugin-com.spwmods.listenstats-1.1.2.zip `
#       -DbFile "$env:APPDATA\Salt Player for Windows\workshop\data\com.spwmods.listenstats\listen-stats.db.properties"
#
# 原理：把真实统计文件复制到临时目录，挂一个只负责"提供数据目录"的假宿主，
# 再调用插件自己的 StatsReport.exportHtml()，所以样式与游戏里生成的完全一致。

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PluginZip,

    [Parameter(Mandatory = $true)]
    [string] $DbFile,

    [string] $OutDir = "$env:TEMP\spw-report-preview"
)

$ErrorActionPreference = 'Stop'

try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $utf8
    $OutputEncoding = $utf8
} catch {
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jc = (Get-Command javac.exe -ErrorAction SilentlyContinue).Source
if ($jc) { $jdkCandidates += (Split-Path (Split-Path $jc)) }
$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) } | Select-Object -First 1
if (-not $jdk) { throw '找不到带 javac 的 JDK，请设置 JAVA_HOME' }

function Resolve-Dep([string] $pattern, [string] $group) {
    $cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
    $hit = Get-ChildItem (Join-Path $cache $group) -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like $pattern -and $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (-not $hit) { throw "找不到依赖 $pattern（先跑一次 gradlew 让它进入缓存）" }
    return $hit.FullName
}

$apiJar = Join-Path $repoRoot 'build\host-api\spw-workshop-api-legacy.jar'
if (-not (Test-Path $apiJar)) { throw "找不到宿主 API 编译桩，请先运行 .\extract-host-api.ps1" }

$pf4jJar = Resolve-Dep 'pf4j-*.jar' 'org.pf4j'
$slf4jJar = Resolve-Dep 'slf4j-api-*.jar' 'org.slf4j'
$kotlinJar = Resolve-Dep 'kotlin-stdlib-2*.jar' 'org.jetbrains.kotlin'

$buildDir = Join-Path $repoRoot 'build\compat-check\preview'
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Force $buildDir | Out-Null

Expand-Archive -Path (Resolve-Path $PluginZip).Path -DestinationPath (Join-Path $buildDir 'unpacked') -Force
$pluginClasses = Join-Path $buildDir 'unpacked\classes'
if (-not (Test-Path (Join-Path $pluginClasses 'com\spwmods\listenstats\StatsReport.class'))) {
    throw "分发包里没有找到插件类，检查 $PluginZip 的结构"
}

$classpath = @($apiJar, $pf4jJar, $slf4jJar, $kotlinJar, $pluginClasses) -join ';'

Write-Host '== 编译预览程序 =='
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -proc:none -cp $classpath -d $buildDir `
    (Join-Path $PSScriptRoot 'PluginLoadHarness.java') (Join-Path $PSScriptRoot 'PreviewReport.java')
if ($LASTEXITCODE -ne 0) { throw '编译失败' }

Write-Host '== 生成报表预览 =='
& (Join-Path $jdk 'bin\java.exe') '-Djava.awt.headless=true' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp "$buildDir;$classpath" PreviewReport $DbFile $OutDir
exit $LASTEXITCODE
