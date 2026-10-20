# Run ONLY after the new launcher works. Disables the stock Google TV launcher and its recommendation rows.
param([string]$Adb = "C:\platform-tools\adb.exe")

Write-Host "Checking the new launcher is the one on screen..."
$top = & $Adb shell dumpsys activity activities | Select-String "topResumedActivity"
Write-Host $top
if ($top -notmatch "com.dk.tvlauncher") {
  Write-Host "The new launcher is not on screen. Press Home on the remote and run this again." -ForegroundColor Red
  exit 1
}

Write-Host "`nWi-Fi networks owned by the setup wizard (must be empty):"
& $Adb shell dumpsys wifi | Select-String "cname=com.google.android.tungsten"

& $Adb shell pm disable-user --user 0 com.google.android.tvlauncher
& $Adb shell pm disable-user --user 0 com.google.android.tvrecommendations
Start-Sleep -Seconds 2
& $Adb shell input keyevent KEYCODE_HOME
Start-Sleep -Seconds 2

Write-Host "`nHOME handlers now:"
& $Adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME | Select-String "packageName="
& $Adb shell dumpsys activity activities | Select-String "topResumedActivity"
New-Item -ItemType Directory -Force -Path shots | Out-Null
& $Adb shell screencap -p /sdcard/shot.png
& $Adb pull /sdcard/shot.png shots\after-stock-disabled.png | Out-Null
& $Adb shell rm /sdcard/shot.png
Write-Host "`nPress Home and Back on the real remote, then send me shots\after-stock-disabled.png."
Write-Host "If anything is wrong: .\scripts\restore.ps1"
