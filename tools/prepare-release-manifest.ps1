param(
    [Parameter(Mandatory)][string]$Tag,
    [Parameter(Mandatory)][string]$WindowsArchive,
    [Parameter(Mandatory)][string]$WindowsVersion,
    [Parameter(Mandatory)][string]$AndroidApk,
    [Parameter(Mandatory)][string]$AndroidVersion,
    [Parameter(Mandatory)][long]$AndroidVersionCode
)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
if ($Tag -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,80}$' -or $Tag.Contains('..')) { throw 'Invalid release tag.' }
foreach ($version in @($WindowsVersion, $AndroidVersion)) {
    if ($version -notmatch '^\d{1,5}\.\d{1,5}\.\d{1,5}$') { throw 'Invalid application version.' }
}
if ($AndroidVersionCode -lt 1) { throw 'Android versionCode must be positive.' }
$windowsFile = Get-Item -LiteralPath $WindowsArchive
$androidFile = Get-Item -LiteralPath $AndroidApk
if ($windowsFile.PSIsContainer -or $windowsFile.Extension -ne '.zip' -or $windowsFile.Length -lt 1 -or $windowsFile.Length -gt 768MB) { throw 'Invalid Windows ZIP.' }
if ($androidFile.PSIsContainer -or $androidFile.Extension -ne '.apk' -or $androidFile.Length -lt 1 -or $androidFile.Length -gt 256MB) { throw 'Invalid Android APK.' }
foreach ($file in @($windowsFile, $androidFile)) {
    if ($file.Name -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]+$') { throw 'Use simple release asset filenames.' }
}
if (!(Test-Path -LiteralPath (Join-Path $projectPath '.secrets/updates-private.pk8'))) { throw 'Release signing key is missing; nothing published.' }
$outputPath = Join-Path $projectPath "artifacts/releases/$Tag"
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$prefix = "https://github.com/Dedseckms/Dedsec-Music/releases/download/$Tag/"
$manifest = [ordered]@{
    schema = 1
    'windows-x64' = [ordered]@{version = $WindowsVersion; url = $prefix + $windowsFile.Name; sha256 = (Get-FileHash -LiteralPath $windowsFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); size = $windowsFile.Length}
    android = [ordered]@{version = $AndroidVersion; versionCode = $AndroidVersionCode; url = $prefix + $androidFile.Name; sha256 = (Get-FileHash -LiteralPath $androidFile.FullName -Algorithm SHA256).Hash.ToLowerInvariant(); size = $androidFile.Length}
}
$manifestPath = Join-Path $outputPath 'updates.json'
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
Push-Location $projectPath
try {
    & (Join-Path $projectPath '.tools/jdk/bin/java.exe') tools/ReleaseSigning.java sign $manifestPath
    if ($LASTEXITCODE -ne 0) { throw 'Manifest signing failed.' }
    & (Join-Path $projectPath '.tools/jdk/bin/java.exe') tools/ReleaseSigning.java verify $manifestPath
    if ($LASTEXITCODE -ne 0) { throw 'Manifest verification failed.' }
} finally { Pop-Location }
Write-Output "Local release manifest prepared: $manifestPath"
Write-Output 'No GitHub release was created and no files were uploaded.'
