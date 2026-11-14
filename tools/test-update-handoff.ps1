$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$fixtureBase = [IO.Path]::GetFullPath((Join-Path $projectPath '.tools/update-handoff-tests'))
$fixturePath = [IO.Path]::GetFullPath((Join-Path $fixtureBase ([guid]::NewGuid().ToString('N'))))
if (!$fixturePath.StartsWith($fixtureBase + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid fixture location.' }
$installPath = Join-Path $fixturePath 'DedSec Music'
$payloadPath = Join-Path $fixturePath 'payload/DedSec Music'
New-Item -ItemType Directory -Path $installPath, $payloadPath -Force | Out-Null
foreach ($folder in @($installPath, $payloadPath)) { Set-Content -LiteralPath (Join-Path $folder '.dedsec-install') -Value fixture }
Set-Content -LiteralPath (Join-Path $installPath 'version.txt') -Value previous
Set-Content -LiteralPath (Join-Path $payloadPath 'version.txt') -Value replacement
$builder = Join-Path $fixturePath 'build-fixture.ps1'
@'
param([string]$OutPath)
Add-Type -OutputAssembly $OutPath -OutputType WindowsApplication -TypeDefinition @"
using System;
using System.IO;
public static class Fixture {
    public static void Main(string[] args) {
        foreach (string arg in args) if (arg.StartsWith("--update-ready=")) File.WriteAllText(arg.Substring(15), "Ready");
    }
}
"@
'@ | Set-Content -LiteralPath $builder -Encoding UTF8
& powershell.exe -NoProfile -File $builder -OutPath (Join-Path $payloadPath 'DedSec Music.exe')
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed.' }
Copy-Item -LiteralPath (Join-Path $payloadPath 'DedSec Music.exe') -Destination (Join-Path $installPath 'DedSec Music.exe')
$archivePath = Join-Path $fixturePath 'update.zip'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory((Split-Path $payloadPath -Parent), $archivePath)
$scriptPath = Join-Path $projectPath 'desktop/src/main/resources/ApplyUpdate.ps1'
function Start-FixtureHelper([string]$Suffix, [int]$ParentId, [int]$Seconds = 15) {
    Start-Process powershell.exe -WindowStyle Hidden -PassThru -WorkingDirectory $fixturePath -ArgumentList @('-NoProfile','-File',('"' + $scriptPath + '"'),'-ProcessId',$ParentId,'-InstallRoot',('"' + $installPath + '"'),'-Archive',('"' + $archivePath + '"'),'-ReadyFile',('"' + (Join-Path $fixturePath ('ready-' + $Suffix)) + '"'),'-ProceedFile',('"' + (Join-Path $fixturePath ('proceed-' + $Suffix)) + '"'),'-HandoffTimeoutSeconds',$Seconds) -RedirectStandardOutput (Join-Path $fixturePath ($Suffix + '.out')) -RedirectStandardError (Join-Path $fixturePath ($Suffix + '.err'))
}
function Wait-Ready([string]$Suffix, $Helper) {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while (!(Test-Path -LiteralPath (Join-Path $fixturePath ('ready-' + $Suffix)))) {
        if ($Helper.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw ('Helper not ready: ' + (Get-Content -LiteralPath (Join-Path $fixturePath ($Suffix + '.err')) -Raw)) }
        Start-Sleep -Milliseconds 100
    }
}
$first = Start-FixtureHelper 'cancelled' $PID 5
Wait-Ready 'cancelled' $first
$second = Start-FixtureHelper 'duplicate' $PID
if (!$second.WaitForExit(10000) -or $second.ExitCode -eq 0 -or (Test-Path -LiteralPath (Join-Path $fixturePath 'ready-duplicate'))) { throw 'Concurrent updater was not rejected.' }
if (!$first.WaitForExit(10000) -or $first.ExitCode -eq 0) { throw 'Unconfirmed helper did not stop.' }
if ((Get-Content -LiteralPath (Join-Path $installPath 'version.txt')).Trim() -ne 'previous') { throw 'Unconfirmed update changed files.' }
$old = Start-Process powershell.exe -WindowStyle Hidden -WorkingDirectory $installPath -ArgumentList @('-NoProfile','-Command','Start-Sleep -Seconds 60') -PassThru
try {
    $success = Start-FixtureHelper 'success' $old.Id
    Wait-Ready 'success' $success
    Set-Content -LiteralPath (Join-Path $fixturePath 'proceed-success') -Value Proceed
    Start-Sleep -Milliseconds 300
    if ((Get-Content -LiteralPath (Join-Path $installPath 'version.txt')).Trim() -ne 'previous') { throw 'Update did not wait for the old process.' }
    if (!$old.HasExited) { $old.Kill(); $old.WaitForExit() }
    if (!$success.WaitForExit(15000) -or $success.ExitCode -ne 0) { throw ('Real handoff failed: ' + (Get-Content -LiteralPath (Join-Path $fixturePath 'success.err') -Raw)) }
    if ((Get-Content -LiteralPath (Join-Path $installPath 'version.txt')).Trim() -ne 'replacement') { throw 'Replacement was not installed.' }
} finally { if (!$old.HasExited) { $old.Kill(); $old.WaitForExit() } }
Write-Output 'Updater native handoff OK: duplicate helper denied, missing confirmation expires, original CWD released, actual process exit and successor acknowledgement verified.'
