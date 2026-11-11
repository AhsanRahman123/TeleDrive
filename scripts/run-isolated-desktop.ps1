param(
    [string]$DataDirectory = ".teledrive-instance"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$previousDataDirectory = $env:TELEDRIVE_DATA_DIR

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
Write-Host "Installed-app default profile:" -ForegroundColor Cyan
if ($env:APPDATA) {
    Write-Host (Join-Path $env:APPDATA "TeleDrive")
} else {
    Write-Host "(APPDATA is not set on this platform)"
}
Write-Host "The installed app profile is not used by this process." -ForegroundColor Green
Write-Host ""

$exitCode = 1
Push-Location $repoRoot
try {
    & (Join-Path $repoRoot "gradlew.bat") --no-daemon :desktop:run
    $exitCode = $LASTEXITCODE
}
finally {
    Pop-Location
    if ([string]::IsNullOrWhiteSpace($previousDataDirectory)) {
        Remove-Item Env:TELEDRIVE_DATA_DIR -ErrorAction SilentlyContinue
    } else {
        $env:TELEDRIVE_DATA_DIR = $previousDataDirectory
    }
}

exit $exitCode
