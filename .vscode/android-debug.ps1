param(
    [string]$AdbPath = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    [string]$PackageName = "com.aiproject.musicplayer",
    [string]$ActivityName = "com.aiproject.musicplayer/.MainActivity",
    [int]$LocalPort = 8700
)

$ErrorActionPreference = "Stop"

if (Get-Variable PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

if (-not (Test-Path $AdbPath)) {
    throw "adb.exe not found at $AdbPath"
}

& $AdbPath start-server | Out-Null
$stateResult = (& $AdbPath get-state 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    if ($stateResult -match "unauthorized") {
        throw "Device is connected but not authorized. Unlock the phone, tap Allow on the USB debugging prompt, optionally enable Always allow from this computer, then rerun the task."
    }
    throw "adb get-state failed: $stateResult"
}

if ($stateResult -ne "device") {
    throw "Authorized Android device not found. Current adb state: $stateResult"
}

& $AdbPath shell am force-stop $PackageName | Out-Null
$forwardList = (& $AdbPath forward --list 2>$null | Out-String).Trim()
if ($forwardList -match "tcp:$LocalPort") {
    & $AdbPath forward --remove "tcp:$LocalPort" | Out-Null
}
& $AdbPath shell am start -D -n $ActivityName | Out-Null

$appPid = ""
for ($attempt = 0; $attempt -lt 15; $attempt++) {
    $appPid = (& $AdbPath shell pidof -s $PackageName 2>$null).Trim()
    if ($appPid) {
        break
    }
    Start-Sleep -Seconds 1
}

if (-not $appPid) {
    throw "App process did not appear. Check the device screen for launch/debug prompts and retry."
}

& $AdbPath forward "tcp:$LocalPort" "jdwp:$appPid" | Out-Null
Write-Host "Debugger tunnel ready on localhost:$LocalPort for pid $appPid"