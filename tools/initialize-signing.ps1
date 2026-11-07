$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$directory = Join-Path $project '.secrets'
$key = Join-Path $directory 'release.p12'
$password = Join-Path $directory 'signing-password.txt'
if (Test-Path -LiteralPath $key) {
    if (!(Test-Path -LiteralPath $password)) { throw 'Existing signing key has no password file. Restore the original; do not replace the key.' }
    Write-Output 'Existing release signing key retained.'
    exit 0
}
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$randomBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
[IO.File]::WriteAllText($password, [Convert]::ToBase64String($randomBytes))
& (Join-Path $project '.tools/jdk/bin/keytool.exe') -genkeypair -keystore $key -storetype PKCS12 -alias dedsec-music -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=DedSec Music, OU=Personal Android App' '-storepass:file' $password '-keypass:file' $password
if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed.' }
Write-Output 'Release signing key created in the ignored .secrets directory. Keep a private backup for updates.'
