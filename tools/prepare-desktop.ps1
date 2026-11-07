$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$archive = Join-Path $project '.tools/vlc-3.0.24-win64.zip'
$destination = Join-Path $project '.tools/desktop-resources/windows/vlc'
$expected = 'fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323'
if (!(Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://downloads.videolan.org/videolan/vlc/3.0.24/win64/vlc-3.0.24-win64.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'VLC archive checksum mismatch.' }
if (!(Test-Path -LiteralPath (Join-Path $destination 'libvlc.dll'))) {
    $expanded = Join-Path $project '.tools/vlc-unpacked'
    Expand-Archive -LiteralPath $archive -DestinationPath $expanded -Force
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    $source = Join-Path $expanded 'vlc-3.0.24'
    foreach ($name in @('libvlc.dll', 'libvlccore.dll', 'plugins', 'COPYING.txt', 'AUTHORS.txt', 'THANKS.txt')) {
        if (Test-Path -LiteralPath (Join-Path $source $name)) { Copy-Item -LiteralPath (Join-Path $source $name) -Destination $destination -Recurse -Force }
    }
}
Write-Output 'Verified VLC 3.0.24 is ready for desktop packaging.'

Push-Location $project
try { & (Join-Path $project ".tools/jdk/bin/java.exe") tools/GenerateBrand.java; if ($LASTEXITCODE -ne 0) { throw "Android icon export failed." } }
finally { Pop-Location }
