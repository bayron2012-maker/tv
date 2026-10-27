# CleanYourTV
Phone app that connects to a TV over ADB (Wi-Fi) and debloats it (Auto + Manual).

## Build the APK (Android Studio does everything)
1. Android Studio > File > Open > select this CleanYourTV folder.
2. Wait for "Gradle sync" to finish (project uses AGP 9.4.0 + Gradle 9.6.0, Kotlin built into AGP). Accept if it offers to install the Android SDK / Platform 35.
3. Build > Build Bundle(s) / APK(s) > Build APK(s) > "locate".
   Result: app/build/outputs/apk/debug/app-debug.apk

Icon: app/src/main/res (adaptive vector icon, already wired in the manifest). Source drawing: design/icon.svg
No PC build? Push this folder to GitHub: .github/workflows/build-apk.yml builds the APK in the Actions tab.
