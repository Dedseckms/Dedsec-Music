param([string]$Version = '0.3.0')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d{1,5}\.\d{1,5}\.\d{1,5}$') { throw 'Invalid version.' }
$projectPath = Split-Path $PSScriptRoot -Parent
$payloadPath = Join-Path $projectPath 'desktop/build/compose/binaries/main/app/DedSec Music'
foreach ($required in @('DedSec Music.exe', 'runtime/bin/java.dll', 'app/resources/vlc/libvlc.dll')) {
    if (!(Test-Path -LiteralPath (Join-Path $payloadPath $required))) { throw "Build distributable first: missing $required" }
}
& (Join-Path $PSScriptRoot 'prepare-installer.ps1')
& (Join-Path $PSScriptRoot 'generate-installer-art.ps1')
Set-Content -LiteralPath (Join-Path $payloadPath '.dedsec-install') -Value 'DedSec Music portable v1' -Encoding ASCII
foreach ($notice in @('LICENSE', 'THIRD_PARTY_NOTICES.md')) { Copy-Item -LiteralPath (Join-Path $projectPath $notice) -Destination $payloadPath -Force }
New-Item -ItemType Directory -Path (Join-Path $payloadPath 'licenses') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectPath 'licenses/windows') -Destination (Join-Path $payloadPath 'licenses') -Recurse -Force
Copy-Item -LiteralPath (Join-Path $projectPath 'docs/WINDOWS.md') -Destination (Join-Path $payloadPath 'README.md') -Force
$outputPath = Join-Path $projectPath 'artifacts/desktop'
& (Join-Path $projectPath '.tools/inno/ISCC.exe') "/DAppVersion=$Version" "/DPayloadDir=$payloadPath" "/DOutputDirPath=$outputPath" (Join-Path $projectPath 'desktop/installer/DedSecMusic.iss')
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed.' }
$installerPath = Join-Path $outputPath "DedSec-Music-$Version-windows-x64-setup.exe"
$digest = (Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath ($installerPath + '.sha256') -Value "$digest  $([IO.Path]::GetFileName($installerPath))" -Encoding ASCII
Write-Output "Installer ready: $installerPath"
