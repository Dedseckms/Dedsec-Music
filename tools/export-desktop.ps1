param([string]$Version = '0.3.0')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d{1,5}\.\d{1,5}\.\d{1,5}$') { throw 'Invalid version.' }
$projectPath = Split-Path $PSScriptRoot -Parent
$applicationPath = Join-Path $projectPath 'desktop/build/compose/binaries/main/app/DedSec Music'
$outputPath = Join-Path $projectPath 'artifacts/desktop'
foreach ($required in @('DedSec Music.exe', 'runtime/bin/java.dll', 'app/resources/vlc/libvlc.dll', 'app/resources/vlc/plugins')) {
    if (!(Test-Path -LiteralPath (Join-Path $applicationPath $required))) { throw "Missing packaged component: $required" }
}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
Set-Content -LiteralPath (Join-Path $applicationPath '.dedsec-install') -Value 'DedSec Music portable v1' -Encoding ASCII
foreach ($notice in @('LICENSE', 'THIRD_PARTY_NOTICES.md')) { Copy-Item -LiteralPath (Join-Path $projectPath $notice) -Destination $applicationPath -Force }
Copy-Item -LiteralPath (Join-Path $projectPath 'docs/WINDOWS.md') -Destination (Join-Path $applicationPath 'README.md') -Force
$archivePath = Join-Path $outputPath "DedSec-Music-$Version-windows-x64.zip"
Add-Type -AssemblyName System.IO.Compression.FileSystem
# Fresh output only: an earlier package is retained under its checksum.
if (Test-Path -LiteralPath $archivePath) {
    $previousHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $retainedPath = Join-Path $outputPath ("previous-$previousHash.zip")
    if (!(Test-Path -LiteralPath $retainedPath)) { Move-Item -LiteralPath $archivePath -Destination $retainedPath }
    else { throw 'Identical prior package already retained; use a new release version.' }
}
[IO.Compression.ZipFile]::CreateFromDirectory($applicationPath, $archivePath, [IO.Compression.CompressionLevel]::Optimal, $true)
$archiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{schema = 1; 'windows-x64' = [ordered]@{version = $Version; url = "https://github.com/Dedseckms/Dedsec-Music/releases/download/desktop-v$Version/DedSec-Music-$Version-windows-x64.zip"; sha256 = $archiveHash; size = (Get-Item -LiteralPath $archivePath).Length}}
$manifestPath = Join-Path $outputPath 'updates.json'
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
Set-Content -LiteralPath (Join-Path $outputPath 'SHA256SUMS.txt') -Value "$archiveHash  $([IO.Path]::GetFileName($archivePath))" -Encoding ASCII
if (Test-Path -LiteralPath (Join-Path $projectPath '.secrets/updates-private.pk8')) {
    Push-Location $projectPath
    try { & (Join-Path $projectPath '.tools/jdk/bin/java.exe') tools/ReleaseSigning.java sign $manifestPath; if ($LASTEXITCODE -ne 0) { throw 'Manifest signing failed.' } }
    finally { Pop-Location }
} else { Write-Output 'Development package only: no release signing key available.' }
Write-Output "Portable package: $archivePath"
Write-Output "SHA256: $archiveHash"
