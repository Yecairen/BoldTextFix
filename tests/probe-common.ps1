$ErrorActionPreference = 'Stop'

function Get-ProbeClasspath {
    param([string]$ProjectRoot, [string]$ClasspathFile, [string]$ModJar)
    if (-not $ClasspathFile) { $ClasspathFile = Join-Path $ProjectRoot 'build/verification/classpath.txt' }
    if (-not (Test-Path -LiteralPath $ClasspathFile -PathType Leaf)) {
        throw 'Missing verification classpath. Build this source checkout first.'
    }
    $paths = @((Get-Content -LiteralPath $ClasspathFile -Raw -Encoding UTF8).Trim().Split([IO.Path]::PathSeparator))
    if ($ModJar) { $paths[0] = (Resolve-Path -LiteralPath $ModJar).Path }
    foreach ($path in $paths) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Missing classpath entry: $path. Rebuild this checkout." }
    }
    return $paths -join [IO.Path]::PathSeparator
}

function New-ProbeRunDirectory {
    param([string]$ProjectRoot, [string]$Suite)
    $directory = Join-Path $ProjectRoot ('build/verification/' + $Suite + '-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory | Out-Null
    return $directory
}

function Invoke-VerificationProbe {
    param([string]$JavaPath, [string]$Classpath, [string]$SourcePath,
          [string[]]$ProbeArguments, [string[]]$VmArguments = @(), [string]$LogPath)
    $javaArguments = @('--enable-native-access=ALL-UNNAMED') + $VmArguments +
        @('--source', '21', '-cp', $Classpath, (Split-Path $SourcePath -Leaf)) + $ProbeArguments
    Push-Location -LiteralPath (Split-Path $SourcePath)
    try {
        # PowerShell 5 treats native stderr as an error record; use the JVM exit code.
        $ErrorActionPreference = 'Continue'
        & $JavaPath @javaArguments 2>&1 | Tee-Object -FilePath $LogPath
        $probeExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($probeExit -ne 0) { throw "Verification failed ($probeExit). See $LogPath" }
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
}
