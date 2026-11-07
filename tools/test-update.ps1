$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$fixturePath = Join-Path $projectPath ('.tools/update-tests/' + [guid]::NewGuid().ToString('N'))
$installPath = Join-Path $fixturePath 'DedSec Music'
$payloadPath = Join-Path $fixturePath 'payload/DedSec Music'
New-Item -ItemType Directory -Path $installPath, $payloadPath -Force | Out-Null
Set-Content -LiteralPath (Join-Path $installPath '.dedsec-install') -Value 'fixture'
Set-Content -LiteralPath (Join-Path $installPath 'DedSec Music.exe') -Value 'previous'
Set-Content -LiteralPath (Join-Path $payloadPath '.dedsec-install') -Value 'fixture'
Set-Content -LiteralPath (Join-Path $payloadPath 'DedSec Music.exe') -Value 'replacement'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archivePath = Join-Path $fixturePath 'update.zip'
[IO.Compression.ZipFile]::CreateFromDirectory((Split-Path $payloadPath -Parent), $archivePath)
$scriptPath = Join-Path $projectPath 'desktop/src/main/resources/ApplyUpdate.ps1'
$readyPath = Join-Path $fixturePath 'ready'
$preflight = & powershell.exe -NoProfile -File $scriptPath -ProcessId $PID -InstallRoot $installPath -Archive $archivePath -ReadyFile $readyPath -CheckOnly 2>&1
if ($LASTEXITCODE -ne 0) { throw "Preflight failed: $preflight" }
if ((Get-Content -LiteralPath (Join-Path $installPath 'DedSec Music.exe')).Trim() -ne 'previous') { throw 'Preflight modified installation.' }
$rollback = & powershell.exe -NoProfile -File $scriptPath -ProcessId $PID -InstallRoot $installPath -Archive $archivePath -ReadyFile $readyPath -TestFailureAfterSwap 2>&1
if ($LASTEXITCODE -eq 0 -or (Get-Content -LiteralPath (Join-Path $installPath 'DedSec Music.exe')).Trim() -ne 'previous') { throw 'Rollback did not restore previous version.' }
$badArchive = Join-Path $fixturePath 'traversal.zip'
$zip = [IO.Compression.ZipFile]::Open($badArchive, [IO.Compression.ZipArchiveMode]::Create)
try { $entry = $zip.CreateEntry('../escape.txt'); $stream = $entry.Open(); $stream.WriteByte(42); $stream.Dispose() } finally { $zip.Dispose() }
$rejected = & powershell.exe -NoProfile -File $scriptPath -ProcessId $PID -InstallRoot $installPath -Archive $badArchive -ReadyFile $readyPath -CheckOnly 2>&1
if ($LASTEXITCODE -eq 0 -or (Test-Path -LiteralPath (Join-Path $fixturePath 'escape.txt'))) { throw 'ZIP traversal was not rejected.' }
Write-Output 'Updater fixtures OK: preflight preserves install, post-swap rollback restores old version, ZIP traversal rejected.'
