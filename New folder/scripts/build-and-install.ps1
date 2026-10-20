# Installs the launcher on the TV and makes it the default home.
# Usage (PowerShell, from the launcher folder):
#   .\scripts\build-and-install.ps1 -Adb C:\platform-tools\adb.exe                 # install a downloaded APK
#   .\scripts\build-and-install.ps1 -Adb C:\platform-tools\adb.exe -Build          # build locally first (needs Gradle + Android SDK)
#   .\scripts\build-and-install.ps1 -Adb C:\platform-tools\adb.exe -Apk C:\path\app-debug.apk
param(
  [string]$Adb = "C:\platform-tools\adb.exe",
  [string]$Apk = "",
  [switch]$Build
)
$ErrorActionPreference = "Stop"
$pkg = "com.dk.tvlauncher"
$act = "$pkg/.MainActivity"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if ($Build) {
  if (Test-Path ".\gradlew.bat") { & .\gradlew.bat assembleDebug } else { & gradle assembleDebug }
  if ($LASTEXITCODE -ne 0) { Write-Host "Build failed." -ForegroundColor Red; exit 1 }
  $Apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
}
if (-not $Apk) {
  foreach ($c in @(".\app-debug.apk", ".\app\build\outputs\apk\debug\app-debug.apk")) { if (Test-Path $c) { $Apk = $c; break } }
}
if (-not $Apk -or -not (Test-Path $Apk)) {
  Write-Host "No APK found. Download it from GitHub Actions (artifact dktvlauncher-debug-apk) or use -Build." -ForegroundColor Red
  exit 1
}

Write-Host "Installing $Apk ..."
& $Adb install -r -t $Apk
if ($LASTEXITCODE -ne 0) {
  Write-Host "adb install failed. Trying push + pm install ..." -ForegroundColor Yellow
  & $Adb push $Apk /data/local/tmp/launcher.apk
  & $Adb shell pm install -r -t /data/local/tmp/launcher.apk
  if ($LASTEXITCODE -ne 0) { Write-Host "Install failed. Send me the error text above." -ForegroundColor Red; exit 1 }
}

# lets the Wi-Fi pill show the network name
& $Adb shell pm grant $pkg android.permission.ACCESS_FINE_LOCATION

Write-Host "`nApps that claim HOME now:"
& $Adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME | Select-String "packageName=|name=com"

& $Adb shell cmd package set-home-activity $act
& $Adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2

New-Item -ItemType Directory -Force -Path shots | Out-Null
& $Adb shell screencap -p /sdcard/shot.png
& $Adb pull /sdcard/shot.png shots\home.png | Out-Null
& $Adb shell rm /sdcard/shot.png
Write-Host "`nTop activity:"
& $Adb shell dumpsys activity activities | Select-String "topResumedActivity"
Write-Host "`nScreenshot saved to shots\home.png - send it to me."
