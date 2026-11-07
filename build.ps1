param([string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug'))
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = Join-Path $PSScriptRoot '.tools\jdk'
$env:ANDROID_HOME = Join-Path $PSScriptRoot '.tools\android-sdk'
$env:GRADLE_USER_HOME = Join-Path $PSScriptRoot '.tools\gradle-cache'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "$PSScriptRoot\.tools\gradle\bin\gradle.bat" -p $PSScriptRoot --no-daemon --max-workers=2 @Tasks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
