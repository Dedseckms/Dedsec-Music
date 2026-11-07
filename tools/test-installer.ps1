param([string]$Version = '0.3.0')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d{1,5}\.\d{1,5}\.\d{1,5}$') { throw 'Invalid version.' }
$projectPath = Split-Path $PSScriptRoot -Parent
$testBase = [IO.Path]::GetFullPath((Join-Path $projectPath '.tools/installer-tests'))
$testRoot = [IO.Path]::GetFullPath((Join-Path $testBase ([guid]::NewGuid().ToString('N'))))
if (!$testRoot.StartsWith($testBase + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected fixture directory.' }
New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
$installRoot = Join-Path $testRoot 'Installed'
$registryKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{F561A2F8-25CC-4A84-9D3D-753CFBB16CA0}_is1'
if (Test-Path -LiteralPath $registryKey) { throw 'An existing installed copy is registered. Fixture installation would replace its registration.' }
$installerPath = Join-Path $projectPath "artifacts/desktop/DedSec-Music-$Version-windows-x64-setup.exe"
$install = Start-Process -FilePath $installerPath -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/SP-','/NOICONS','/TASKS=""',('/DIR="' + $installRoot + '"'),('/LOG="' + (Join-Path $testRoot 'setup.log') + '"')) -WindowStyle Hidden -Wait -PassThru
if ($install.ExitCode -ne 0) { throw "Install fixture failed: $($install.ExitCode)" }
$payload = [IO.Path]::GetFullPath((Join-Path $installRoot 'DedSec Music'))
if (!$payload.StartsWith($testRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or !(Test-Path -LiteralPath (Join-Path $payload '.dedsec-install'))) { throw 'Unexpected installed payload.' }
foreach ($required in @('DedSec Music.exe', 'app/resources/vlc/libvlc.dll', 'runtime/bin/java.dll')) {
    if (!(Test-Path -LiteralPath (Join-Path $payload $required))) { throw "Missing installed file: $required" }
}
$audio = Start-Process -FilePath (Join-Path $payload 'DedSec Music.exe') -ArgumentList '--audio-smoke' -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput (Join-Path $testRoot 'audio.out') -RedirectStandardError (Join-Path $testRoot 'audio.err')
if ($audio.ExitCode -ne 0) { throw 'Installed app audio fixture failed.' }
if ((Get-Content -LiteralPath (Join-Path $testRoot 'audio.out') -Raw) -notlike '*VLC smoke OK*') { throw 'Installed app did not finish audio check.' }
# Simulate a file introduced by a later in-app update, outside Inno's original file log.
Set-Content -LiteralPath (Join-Path $payload 'updated-payload-fixture.txt') -Value 'new version'
$sentinel = Join-Path $testRoot 'outside-payload-keep.txt'
Set-Content -LiteralPath $sentinel -Value 'retain'
$uninstallerPath = [IO.Path]::GetFullPath((Join-Path $installRoot 'unins000.exe'))
if (!$uninstallerPath.StartsWith($testRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected uninstaller target.' }
$uninstall = Start-Process -FilePath $uninstallerPath -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',('/LOG="' + (Join-Path $testRoot 'uninstall.log') + '"')) -WindowStyle Hidden -Wait -PassThru
if ($uninstall.ExitCode -ne 0 -or (Test-Path -LiteralPath $payload) -or !(Test-Path -LiteralPath $sentinel) -or (Test-Path -LiteralPath $registryKey)) { throw 'Uninstall fixture did not preserve boundaries.' }
Write-Output 'Installer fixture OK: per-user install, bundled runtime/audio, updated payload cleanup and removal of registration; outside files retained.'
