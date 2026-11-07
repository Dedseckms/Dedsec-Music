param([Parameter(Mandatory)][int]$ProcessId, [Parameter(Mandatory)][string]$InstallRoot, [Parameter(Mandatory)][string]$Archive, [Parameter(Mandatory)][string]$ReadyFile, [switch]$CheckOnly, [switch]$TestFailureAfterSwap)
$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($InstallRoot).TrimEnd('\')
$parentPath = Split-Path $rootPath -Parent
if ((Split-Path $rootPath -Leaf) -ne 'DedSec Music' -or !(Test-Path -LiteralPath (Join-Path $rootPath '.dedsec-install'))) { throw 'Unexpected install directory.' }
if ((Get-Item -LiteralPath $rootPath).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked installations are not updated automatically.' }
$stagePath = Join-Path (Split-Path ([IO.Path]::GetFullPath($Archive)) -Parent) ('unpack-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stagePath | Out-Null
# The archive has already passed signature, exact size and SHA256 verification.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($Archive)
try {
    $expanded = 0L
    foreach ($entry in $zip.Entries) {
        $targetPath = [IO.Path]::GetFullPath((Join-Path $stagePath $entry.FullName))
        if (!$targetPath.StartsWith($stagePath + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe archive path.' }
        $expanded += $entry.Length
        if ($expanded -gt 2GB) { throw 'Expanded update is too large.' }
    }
} finally { $zip.Dispose() }
Expand-Archive -LiteralPath $Archive -DestinationPath $stagePath
$payloadPath = Join-Path $stagePath 'DedSec Music'
if (!(Test-Path -LiteralPath (Join-Path $payloadPath '.dedsec-install')) -or !(Test-Path -LiteralPath (Join-Path $payloadPath 'DedSec Music.exe'))) { throw 'Incomplete update package.' }
$nextPath = [IO.Path]::GetFullPath((Join-Path $parentPath ('DedSec Music.new-' + [guid]::NewGuid().ToString('N'))))
$backupPath = [IO.Path]::GetFullPath((Join-Path $parentPath ('DedSec Music.backup-' + [guid]::NewGuid().ToString('N'))))
# Verify every recursive copy/move target stays beside this exact installation.
foreach ($candidatePath in @($rootPath, $nextPath, $backupPath)) {
    if ((Split-Path $candidatePath -Parent) -ne $parentPath -or (Split-Path $candidatePath -Leaf) -notlike 'DedSec Music*') { throw 'Unexpected update target.' }
}
Copy-Item -LiteralPath $payloadPath -Destination $nextPath -Recurse
if ($CheckOnly) { Write-Output 'Update package preflight OK; installation unchanged.'; exit 0 }
$failedPath = Join-Path $parentPath ('DedSec Music.failed-' + [guid]::NewGuid().ToString('N'))
if ((Split-Path ([IO.Path]::GetFullPath($failedPath)) -Parent) -ne $parentPath) { throw 'Unexpected recovery target.' }
$launchReady = Join-Path (Split-Path ([IO.Path]::GetFullPath($Archive)) -Parent) ('launch-ready-' + [guid]::NewGuid().ToString('N'))
[IO.File]::WriteAllText([IO.Path]::GetFullPath($ReadyFile), 'Ready')
if (!$TestFailureAfterSwap) { Wait-Process -Id $ProcessId -ErrorAction SilentlyContinue }
$oldMoved = $false
$newMoved = $false
$newProcess = $null
try {
    Move-Item -LiteralPath $rootPath -Destination $backupPath
    $oldMoved = $true
    Move-Item -LiteralPath $nextPath -Destination $rootPath
    $newMoved = $true
    if ($TestFailureAfterSwap) { throw 'Injected failure after swap.' }
    $newProcess = Start-Process -FilePath (Join-Path $rootPath 'DedSec Music.exe') -ArgumentList ('"--update-ready=' + $launchReady + '"') -WorkingDirectory $rootPath -WindowStyle Normal -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    while (!(Test-Path -LiteralPath $launchReady)) {
        if ($newProcess.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Updated application did not become ready.' }
        Start-Sleep -Milliseconds 200
    }
} catch {
    if ($null -ne $newProcess -and !$newProcess.HasExited) { Stop-Process -Id $newProcess.Id; $newProcess.WaitForExit() }
    if ($newMoved -and (Test-Path -LiteralPath $rootPath)) { Move-Item -LiteralPath $rootPath -Destination $failedPath }
    if ($oldMoved -and !(Test-Path -LiteralPath $rootPath) -and (Test-Path -LiteralPath $backupPath)) {
        Move-Item -LiteralPath $backupPath -Destination $rootPath
        if (!$TestFailureAfterSwap) { Start-Process -FilePath (Join-Path $rootPath 'DedSec Music.exe') -WorkingDirectory $rootPath -WindowStyle Normal }
    }
    throw
}
# Retain backup and failed folders for recovery; user data lives separately.
