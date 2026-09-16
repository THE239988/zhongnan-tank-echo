param([string]$OutputDirectory = (Join-Path $PSScriptRoot '..'))
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    & mvn -B package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    $javaExecutable = (Get-Command java).Source
    $taskJdkRoot = Split-Path (Split-Path $javaExecutable -Parent) -Parent
    $jlink = Join-Path $taskJdkRoot 'bin/jlink.exe'
    if (-not (Test-Path -LiteralPath $jlink)) { throw 'A full JDK 17+ with jlink is required.' }
    $stagePath = Join-Path $PSScriptRoot ('target/portable-' + [guid]::NewGuid().ToString('N'))
    $gamePath = Join-Path $stagePath 'TankTrouble-3.0.2'
    New-Item -ItemType Directory -Path (Join-Path $gamePath 'target/lib') -Force | Out-Null
    Copy-Item -LiteralPath 'target/tanktrouble-javafx-3.0.2.jar' -Destination (Join-Path $gamePath 'target')
    Copy-Item -Path 'target/lib/*.jar' -Destination (Join-Path $gamePath 'target/lib')
    foreach ($name in @('run.bat','README.md','DEVELOPMENT.md','THIRD_PARTY.md')) {
        Copy-Item -LiteralPath $name -Destination $gamePath
    }
    if (Test-Path -LiteralPath 'assets') {
        Copy-Item -LiteralPath 'assets' -Destination $gamePath -Recurse
    }
    & $jlink --add-modules java.base,java.desktop,java.logging,java.xml,jdk.unsupported,jdk.charsets --strip-debug --no-header-files --no-man-pages --compress=2 --output (Join-Path $gamePath 'runtime')
    if ($LASTEXITCODE -ne 0) { throw 'Runtime generation failed.' }
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
    $archive = Join-Path $OutputDirectory 'TankTrouble-3.0.2-Windows.zip'
    Compress-Archive -LiteralPath $gamePath -DestinationPath $archive -Force
    Write-Output ('Portable folder: ' + $gamePath)
    Write-Output ('Release archive: ' + (Resolve-Path -LiteralPath $archive))
} finally { Pop-Location }
