param(
    [string]$JavaPath = 'java',
    [string]$ClasspathFile,
    [string]$ModJar,
    [Parameter(Mandatory=$true)][string]$MinecraftJar,
    [switch]$ExpectMissing
)
$ErrorActionPreference = 'Stop'
$projectRoot = (Get-Location).Path
$common = Join-Path $projectRoot 'tests/probe-common.ps1'
if (-not (Test-Path -LiteralPath $common)) { $common = Join-Path $projectRoot 'verification/probe-common.ps1' }
. $common
$resolved = Get-ProbeClasspath -ProjectRoot $projectRoot -ClasspathFile $ClasspathFile -ModJar $ModJar
$entries = $resolved.Split([IO.Path]::PathSeparator)
$artifact = $entries[0]
$vanillaClient = (Resolve-Path -LiteralPath $MinecraftJar).Path
$entries = $entries | ForEach-Object {
    if ($_.Replace('\', '/') -match '/net/minecraft/minecraft-[^/]+/') { $vanillaClient } else { $_ }
}
$classpath = ($entries | Where-Object {
    $normalized = $_.Replace('\', '/')
    $_ -ne $artifact -and -not $normalized.Contains('/libs/') -and
        -not $normalized.Contains('/net.fabricmc.fabric-api/') -and
        -not $normalized.Contains('/mixinextras-fabric/')
}) -join [IO.Path]::PathSeparator
$runDirectory = New-ProbeRunDirectory -ProjectRoot $projectRoot -Suite 'language-resources'
$gameDirectory = Join-Path $runDirectory 'game'
New-Item -ItemType Directory -Path (Join-Path $gameDirectory 'mods') -Force | Out-Null
Copy-Item -LiteralPath $artifact -Destination (Join-Path $gameDirectory 'mods/boldtextfix.jar')
$probeArguments = @($artifact, $gameDirectory)
if ($ExpectMissing) { $probeArguments += '--expect-missing' }
$probeSource = Join-Path $runDirectory 'LanguageResourcesProbe.java'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'LanguageResourcesProbe.java') -Destination $probeSource
Invoke-VerificationProbe -JavaPath $JavaPath -Classpath $classpath -SourcePath $probeSource -ProbeArguments $probeArguments -VmArguments @('-Djava.awt.headless=true') -LogPath (Join-Path $runDirectory 'languages.log')
Write-Output "Language resource verification completed. Results: $runDirectory"
