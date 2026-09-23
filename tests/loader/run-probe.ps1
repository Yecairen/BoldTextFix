param(
    [string]$JavaPath = 'java',
    [string]$ClasspathFile,
    [string]$ModJar
)
$ErrorActionPreference = 'Stop'
# Run from the repository root so all temporary data stays in its build directory.
$projectRoot = (Get-Location).Path
. (Join-Path $projectRoot 'tests/probe-common.ps1')
$resolved = Get-ProbeClasspath -ProjectRoot $projectRoot -ClasspathFile $ClasspathFile -ModJar $ModJar
$entries = $resolved.Split([IO.Path]::PathSeparator)
$artifact = $entries[0]
$classpath = ($entries | Where-Object {
    $normalized = $_.Replace('\', '/')
    $_ -ne $artifact -and -not $normalized.Contains('/libs/') -and
        -not $normalized.Contains('/net.fabricmc.fabric-api/') -and
        -not $normalized.Contains('/mixinextras-fabric/')
}) -join [IO.Path]::PathSeparator
$runDirectory = New-ProbeRunDirectory -ProjectRoot $projectRoot -Suite 'loader'
$gameDirectory = Join-Path $runDirectory 'game'
New-Item -ItemType Directory -Path (Join-Path $gameDirectory 'mods') -Force | Out-Null
Copy-Item -LiteralPath $artifact -Destination (Join-Path $gameDirectory 'mods/boldtextfix.jar')
Invoke-VerificationProbe -JavaPath $JavaPath -Classpath $classpath -SourcePath (Join-Path $PSScriptRoot 'MixinAudit.java') -ProbeArguments @($artifact, $gameDirectory) -VmArguments @('-Djava.awt.headless=true') -LogPath (Join-Path $runDirectory 'loader.log')
Write-Output "Fabric/Mixin loading verification passed. Results: $runDirectory"
