param(
    [string]$MavenRepository = $env:MAVEN_REPO
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $arguments = @('-B')
    if ($MavenRepository) {
        $arguments += "-Dmaven.repo.local=$MavenRepository"
    }
    $arguments += @('package')
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE."
    }
    Write-Output "Built: $PSScriptRoot\target\tanktrouble-javafx-3.0.2.jar"
} finally {
    Pop-Location
}
