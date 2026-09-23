param(
    [string]$JavaPath = 'java',
    [string]$ClasspathFile,
    [string]$ModJar
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $projectRoot 'tests/probe-common.ps1')
$classpath = Get-ProbeClasspath -ProjectRoot $projectRoot -ClasspathFile $ClasspathFile -ModJar $ModJar
$runDirectory = New-ProbeRunDirectory -ProjectRoot $projectRoot -Suite 'cache'
$isolated = Join-Path $runDirectory 'isolated'
foreach ($phase in @('write', 'read')) {
    $probe = @{
        JavaPath = $JavaPath
        Classpath = $classpath
        SourcePath = Join-Path $PSScriptRoot 'CacheProbe.java'
        ProbeArguments = @($isolated, $phase)
        LogPath = Join-Path $runDirectory ($phase + '.log')
    }
    Invoke-VerificationProbe @probe
}
Write-Output "Cache verification passed in two JVMs. Results: $runDirectory"
