$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
$archivePath = Join-Path $projectPath '.tools/innosetup-7.1.1-x64.exe'
$compilerPath = Join-Path $projectPath '.tools/inno'
$expected = '8a5a6711c3766a6e5fb74f280ba0e3551e719ce94538d901fdbd1f8f9920b3f0'
New-Item -ItemType Directory -Path (Split-Path $archivePath -Parent) -Force | Out-Null
if (!(Test-Path -LiteralPath $archivePath)) { Invoke-WebRequest 'https://github.com/jrsoftware/issrc/releases/download/is-7_1_1/innosetup-7.1.1-x64.exe' -OutFile $archivePath }
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Inno compiler checksum mismatch.' }
$signature = Get-AuthenticodeSignature -LiteralPath $archivePath
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike '*Pyrsys*') { throw 'Inno compiler publisher verification failed.' }
if (!(Test-Path -LiteralPath (Join-Path $compilerPath 'ISCC.exe'))) {
    $compilerProcess = Start-Process -FilePath $archivePath -ArgumentList @('/PORTABLE=1','/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/SP-','/NOICONS',('/DIR="' + $compilerPath + '"')) -WindowStyle Hidden -Wait -PassThru
    if ($compilerProcess.ExitCode -ne 0) { throw 'Portable compiler preparation failed.' }
}
Write-Output 'Verified portable Inno Setup 7.1.1 compiler ready.'
