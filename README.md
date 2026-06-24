# JellyWatch

A small, watch-native Jellyfin client built for Samsung Galaxy Watch 7 and other modern Wear OS watches.

## Features

- Secure Jellyfin Quick Connect sign-in — no phone companion app required
- Configurable home sections for Continue Watching, Libraries, Next Up, and Recently Added
- Browse Jellyfin libraries
- Search movies, shows, episodes, video, and music
- Resume playback and report progress to Jellyfin
- HLS playback capped at 480×480 / 2.2 Mbps for a sensible watch experience
- Supports HTTPS with public or user-installed certificate authorities, plus plain HTTP on trusted home networks

## Build

Requirements: JDK 17 and Android SDK 36.

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK will be written to `app/build/outputs/apk/debug/app-debug.apk`.

## Install

Enable wireless debugging on the watch, pair/connect ADB, then run:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## GitHub builds and releases

The GitHub Actions workflow builds and lints the installable APK on pushes to `main`, pull requests, and manual runs. Download it from the run's **Artifacts** section.

To publish the APK on a GitHub Release, push a version tag:

```powershell
git tag v1.0.0
git push origin v1.0.0
```

The workflow creates the release and attaches `JellyWatch-v1.0.0.apk`. The current artifact uses Android's debug signature and is intended for direct sideloading.

On first launch, enter the full Jellyfin server URL (for example `https://jellyfin.example.com` or `http://192.168.1.10:8096`). The watch shows a short Quick Connect code; approve it from the user menu in Jellyfin on your phone or computer. Quick Connect must be enabled on the server, and the watch must be able to reach the server address directly.

HTTPS certificates issued by a public certificate authority work automatically. For a private home certificate authority, install that CA certificate as a trusted user credential on the watch first. Self-signed certificates are not accepted without establishing that trust; certificate validation is never disabled.

## Notes

This MVP intentionally focuses on the useful watch loop: find something quickly, resume it, and play it. Downloads, subtitles, server discovery, Android Auto, casting, and a phone companion are not included yet.

Credentials are stored in the app's private SharedPreferences. For untrusted networks, expose Jellyfin through HTTPS rather than plain HTTP.
