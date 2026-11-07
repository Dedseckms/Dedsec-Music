$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$env:JAVA_HOME = Join-Path $project '.tools/jdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$source = Join-Path $project 'app/build/outputs/apk/release/app-release.apk'
$metadata = Get-Content -LiteralPath (Join-Path $project 'app/build/outputs/apk/release/output-metadata.json') -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
if ($version -notmatch '^\d+\.\d+\.\d+([-.][a-zA-Z0-9.]+)?$') { throw 'Unexpected APK version.' }
& (Join-Path $project '.tools/android-sdk/build-tools/35.0.0/apksigner.bat') verify --verbose $source
if ($LASTEXITCODE -ne 0) { throw 'Release APK signature is invalid or missing.' }
$artifacts = Join-Path $project 'artifacts'
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$filename = "DedSec-Music-$version.apk"
$destination = Join-Path $artifacts $filename
Copy-Item -LiteralPath $source -Destination $destination
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($destination + '.sha256'), "$hash  $filename`n")
Copy-Item -LiteralPath (Join-Path $project 'README.md') -Destination (Join-Path $artifacts 'README.md')
Copy-Item -LiteralPath (Join-Path $project 'VALIDATION.md') -Destination (Join-Path $artifacts 'VALIDATION.md')
Write-Output ('APK ready: ' + $destination)
Write-Output ('SHA256: ' + $hash)
