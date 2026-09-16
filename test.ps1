param(
    [switch]$Gui,
    [string]$MavenRepository = $env:MAVEN_REPO
)

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    $arguments = @('-B')
    if ($MavenRepository) {
        $arguments += "-Dmaven.repo.local=$MavenRepository"
    }
    $arguments += 'test'
    if ($Gui) {
        $arguments += '-DfxSmoke=true'
        $arguments += '-Dtanktrouble.dataDir=target/test-data'
        $arguments += '-Dtanktrouble.screenshots=target/ui-screenshots'
    }
    & mvn @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven tests failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}
