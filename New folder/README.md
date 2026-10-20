# DK TV launcher (OnePlus TV Y1S Pro, Android 11)

An Apple TV style home screen: black background, dock with four apps, a 4-column grid below,
one glass highlight that springs between tiles, Outfit font. Plain Android Views, no dependencies,
no blur, no video, no network polling.

## Get the APK

I could not compile it where I wrote it, so build it one of these ways:

1. **GitHub Actions (no installs):** put this folder in a new GitHub repository. The workflow in
   `.github/workflows/build.yml` builds on every push; download the artifact `dktvlauncher-debug-apk`
   (it contains `app-debug.apk`).
2. **Android Studio:** open this folder, let it sync, then Build > Build APK(s).

## Install

```
.\scripts\build-and-install.ps1 -Adb C:\platform-tools\adb.exe -Apk C:\path\to\app-debug.apk
```
It installs the APK, grants the Wi-Fi-name permission, makes it the default home and saves a screenshot to `shots\`.
Once you have checked it works, retire the stock launcher:
```
.\scripts\disable-stock-launcher.ps1 -Adb C:\platform-tools\adb.exe
```

## Undo / re-enable apps

`.\scripts\restore.ps1` re-enables every package we disabled and makes the stock launcher the home again.
To re-enable one app: `adb shell pm enable <package>`. To list what is disabled: `adb shell pm list packages -d`.

## Change the dock

Edit `DOCK_PKGS` at the top of `app/src/main/java/com/dk/tvlauncher/MainActivity.java`
(package names, in the order you want), rebuild, reinstall. Everything else lands in the grid, sorted by name.
Apps you never want shown go in `HIDDEN_PKGS`.

## Wallpaper

The background is pitch black on purpose (Apple TV style, and the lightest option for a TV with little RAM).
There is no wallpaper feature yet. Tell me if you want one and I will add a picture background.

## Keys

Up/Down move exactly one row. Up from the dock goes to the top bar (Inputs, Wi-Fi, Settings);
the ends of the bar do not fall through. OK opens, Back from the grid returns to the dock.
The last focused dock or grid tile is remembered.

## Optional: ad-blocking DNS

`adb shell settings put global private_dns_specifier dns.adguard-dns.com` then
`adb shell settings put global private_dns_mode hostname`.
It does not block YouTube ads. Undo: `adb shell settings put global private_dns_mode opportunistic`.

## When you are completely done

Switch USB debugging off on the TV, revoke USB debugging authorisations (Developer options), reboot, and check
that `Test-NetConnection 192.168.29.211 -Port 5555` fails. Port 5555 is plaintext.

Font: Outfit, SIL Open Font License (see OFL.txt).
