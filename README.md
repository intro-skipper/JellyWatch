# JellyWatch

A small, watch-native Jellyfin client built for Samsung Galaxy Watch 7 and other modern Wear OS watches.

## Features

- **Secure Sign-in**: Jellyfin Quick Connect support — sign in via a short code without typing passwords on a tiny keyboard. No phone companion app required.
- **Smart Playback**: Attempts **direct playback** first for maximum quality and efficiency, with automatic fallback to **HLS transcoding** if the watch hardware doesn't support the source format.
- **Skip Segments**: Jump past intros, credits, and commercials using Jellyfin Media Segments integration.
- **Configurable Home**: Fully customize your home screen. Enable, disable, or reorder sections like *Continue Watching*, *Libraries*, *Next Up*, and *Recently Added* to suit your viewing habits.
- **Pro Navigation**:
  - **Long-press** any item in *Continue Watching* on the home screen to jump straight to its season or parent library.
  - **Long-press** items in library or search results to view full details and overviews before playing.
- **Video Sizing**: Toggle between **Fit** and **Stretch** (Fill) modes in the player to make the best use of round watch displays.
- **Optimized Experience**:
  - UI designed specifically for circular screens with high-contrast, easy-to-tap controls.
  - Transcoding is capped at 480×480 / 2.2 Mbps for smooth streaming on wearable hardware.
- **Connectivity**: Supports HTTPS with public or user-installed certificate authorities, plus plain HTTP on trusted home networks.

## Build

Requirements: JDK 17 and Android SDK 36.

```powershell
.\gradlew.bat :app:assembleRelease
```

The APK will be written to `app/build/outputs/apk/release/app-release.apk`.

## Install

Enable wireless debugging on the watch, pair/connect ADB, then run:

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

## GitHub Builds and Releases

The GitHub Actions workflow builds and lints the APK on every push to `main`. 

- **Artifacts**: Download the latest build from the **Actions** tab by selecting a recent run.
- **Releases**: Manual releases can be triggered via `workflow_dispatch` in GitHub Actions, which creates a tagged release with the versioned APK.

## Usage

1. **Sign In**: Enter the full Jellyfin server URL (e.g., `https://jellyfin.example.com`). The watch will display a Quick Connect code.
2. **Approve**: On your phone or computer, open Jellyfin → User Menu → Quick Connect and enter the code.
3. **Customize**: Tap the options icon on the Home screen to reorder sections, customize the layout, or sign out.

## Notes

This MVP intentionally focuses on the "watch loop": find something quickly, resume it, and play it.

- **Certificates**: HTTPS certificates from public CAs work automatically. For private/self-signed CAs, you must install the CA certificate as a trusted user credential on the watch first. Certificate validation is never disabled for security.
- **Omissions**: Subtitles, offline downloads, and server discovery are not yet implemented.
- **Privacy**: See [PRIVACY.md](PRIVACY.md). JellyWatch stores the Jellyfin token and app preferences in the app's private SharedPreferences and does not include analytics, ads, or crash-reporting SDKs. Always use HTTPS when connecting over untrusted networks.
