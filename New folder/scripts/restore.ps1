# Undoes everything: re-enables every disabled package and puts the stock launcher back as home.
param([string]$Adb = "C:\platform-tools\adb.exe")
$pkgs = @(
  "com.google.android.tvlauncher",
  "com.google.android.tvrecommendations",
  "com.google.android.apps.mediashell",
  "com.heytap.optvmiracastservice",
  "com.google.android.youtube.tvmusic",
  "com.google.android.videos",
  "com.google.android.play.games",
  "com.oneplus.tv.userguide",
  "com.netflix.ninja",
  "com.google.android.youtube.tv",
  "com.google.android.backdrop",
  "com.oneplus.tv.screensaver",
  "net.oneplus.weather",
  "com.coloros.relax.tv"
)
foreach ($p in $pkgs) { & $Adb shell pm enable $p }
& $Adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
& $Adb shell input keyevent KEYCODE_HOME
Write-Host "Restored. To remove the custom launcher completely: adb shell pm uninstall com.dk.tvlauncher"
