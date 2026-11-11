param(
    [string]$DataDirectory = ".teledrive-instance"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if ([System.IO.Path]::IsPathRooted($DataDirectory)) {
    $profileRoot = [System.IO.Path]::GetFullPath($DataDirectory)
} else {
    $profileRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $DataDirectory))
}

New-Item -ItemType Directory -Force -Path $profileRoot | Out-Null
$env:TELEDRIVE_DATA_DIR = $profileRoot

Write-Host ""
Write-Host "TeleDrive source checkout :" -ForegroundColor Cyan
Write-Host $repoRoot
Write-Host "Isolated TeleDrive profile:" -ForegroundColor Cyan
Write-Host $profileRoot
Write-Host "The installed app profile is not used by this process." -ForegroundColor Green
Write-Host ""

Push-Location $repoRoot
try {
    & (Join-Path $repoRoot "gradlew.bat") --no-daemon :desktop:run
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
