# 用真实 PF4J + 旧宿主（SPW 1.18.5）同款加载流程验证分发包。
#
#   powershell -ExecutionPolicy Bypass -File .\run-verify.ps1 -PluginZip ..\..\build\libs\plugin-com.spwmods.listenstats-1.1.2.zip
#
# 前置：先跑 extract-host-api.ps1（生成 build/host-api/spw-workshop-api-legacy.jar）
#
# 检查项（共 20 项，退出码 0 表示全过）：
#   1. 分发包能被识别（Manifest 在 classes/META-INF/MANIFEST.MF）
#   2. 未写 enabled.txt 时插件保持 DISABLED、扩展点不激活（旧宿主是白名单）
#   3. 写入插件 ID 后插件 STARTED，并暴露 1 个 PlaybackExtensionPoint
#   4. 驱动播放回调：听满 30 秒计 1 次、单曲循环再计 1 次、收听时长落盘

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PluginZip,

    # 运行期工作目录（只有其中的 plugins / data 会被清空重建）
    [string] $WorkDir = "$env:TEMP\spw-plugin-verify",

    # 宿主 API 编译桩；留空则用 build/host-api/spw-workshop-api-legacy.jar
    [string] $ApiJar = '',

    # 依赖 jar 目录；留空则从 Gradle 缓存里找
    [string] $LibDir = ''
)

$ErrorActionPreference = 'Stop'

try {
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [Console]::OutputEncoding = $utf8
    $OutputEncoding = $utf8
} catch {
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

# ---------------------------------------------------------------- JDK
$jdkCandidates = @()
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jc = (Get-Command javac.exe -ErrorAction SilentlyContinue).Source
if ($jc) { $jdkCandidates += (Split-Path (Split-Path $jc)) }
$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\javac.exe')) } | Select-Object -First 1
if (-not $jdk) { throw '找不到带 javac 的 JDK，请设置 JAVA_HOME' }

# ---------------------------------------------------------------- 依赖
function Resolve-Dep([string] $pattern, [string] $group) {
    if ($LibDir) {
        $hit = Get-ChildItem $LibDir -Filter $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    $cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
    $hit = Get-ChildItem (Join-Path $cache $group) -Recurse -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like $pattern -and $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if (-not $hit) { throw "找不到依赖 $pattern（可用 -LibDir 指定目录；先跑一次 gradlew 让它进入缓存）" }
    return $hit.FullName
}

if (-not $ApiJar) {
    $ApiJar = Join-Path $repoRoot 'build\host-api\spw-workshop-api-legacy.jar'
}
if (-not (Test-Path $ApiJar)) {
    throw "找不到宿主 API 编译桩: $ApiJar`n请先运行 .\extract-host-api.ps1"
}

$apiJarPath = (Resolve-Path $ApiJar).Path
$pf4jJar = Resolve-Dep 'pf4j-*.jar' 'org.pf4j'
$slf4jJar = Resolve-Dep 'slf4j-api-*.jar' 'org.slf4j'
$kotlinJar = Resolve-Dep 'kotlin-stdlib-2*.jar' 'org.jetbrains.kotlin'

$classpath = @($apiJarPath, $pf4jJar, $slf4jJar, $kotlinJar) -join ';'
$outDir = Join-Path $repoRoot 'build\compat-check\classes'
New-Item -ItemType Directory -Force $outDir | Out-Null

Write-Host '== 编译验证程序 =='
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -proc:none -cp $classpath -d $outDir `
    (Join-Path $PSScriptRoot 'PluginLoadHarness.java')
if ($LASTEXITCODE -ne 0) { throw '编译失败' }

Write-Host '== 运行验证 =='
& (Join-Path $jdk 'bin\java.exe') '-Djava.awt.headless=true' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    -cp "$outDir;$classpath" PluginLoadHarness $WorkDir ((Resolve-Path $PluginZip).Path)
exit $LASTEXITCODE
