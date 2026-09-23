param(
    [Parameter(Mandatory = $true)][string]$TrueTypeFont,
    [Parameter(Mandatory = $true)][string]$AssetsDirectory,
    [string]$AssetIndex = '34',
    [string]$JavaPath = 'java',
    [string]$ClasspathFile,
    [string]$ModJar
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot '../probe-common.ps1')
$font = (Resolve-Path -LiteralPath $TrueTypeFont).Path
$assets = (Resolve-Path -LiteralPath $AssetsDirectory).Path
if (-not (Test-Path -LiteralPath (Join-Path $assets ('indexes/' + $AssetIndex + '.json')))) {
    throw "Minecraft asset index $AssetIndex is missing. Supply the assets directory for Minecraft 26.3."
}
$classpath = Get-ProbeClasspath -ProjectRoot $projectRoot -ClasspathFile $ClasspathFile -ModJar $ModJar
$runDirectory = New-ProbeRunDirectory -ProjectRoot $projectRoot -Suite 'settings'
$probe = @{
    JavaPath = $JavaPath
    Classpath = $classpath
    SourcePath = Join-Path $PSScriptRoot 'CustomFontProbe.java'
    ProbeArguments = @((Join-Path $runDirectory 'isolated'))
    VmArguments = @("-Dboldtextfix.test.font=$font", "-Dboldtextfix.test.assets=$assets", "-Dboldtextfix.test.assetIndex=$AssetIndex")
    LogPath = Join-Path $runDirectory 'settings.log'
}
Invoke-VerificationProbe @probe
Write-Output "Settings verification passed. Results: $runDirectory"
