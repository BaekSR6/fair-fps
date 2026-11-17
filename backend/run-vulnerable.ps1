$ErrorActionPreference = 'Stop'

if (-not $env:FAIRFPS_ADMIN_KEY) {
    throw 'Set FAIRFPS_ADMIN_KEY before starting the vulnerable server.'
}

$env:FAIRFPS_MODE = 'vulnerable'
Set-Location $PSScriptRoot
mvn spring-boot:run
