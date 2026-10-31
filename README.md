# SERVI Browser 1.3.0 — Android source project

A native Kotlin Android browser with a clean browser-style spiral emblem, deep-navy styling, and privacy-first defaults. The symbol contains no initials or letters; the SERVI name is shown separately in the app header and splash screen. It uses Android System WebView; it is not an HTML wrapper and it is not a full Chromium replacement.

## Included features

- Native Android app (`com.servi.browser`), min Android 8.0 (API 26)
- New three-ribbon browser emblem in electric blue, cyan, and violet; no letters inside the icon
- Animated logo in the app header and splash screen: rotating orbital light, glowing trail, and subtle pulse
- SERVI wordmark is shown as separate app text below the symbol on the loading screen and beside it in the browser header
- Animated splash/loading screen
- Search/address bar with Google, Bing, DuckDuckGo and Yahoo options
- Back, forward, reload, home, up to 12 tabs, new tab and close-all-tabs
- WebView download support through Android DownloadManager
- Incognito mode with a visible indicator; temporary web data is cleared when switching sessions
- Lightweight tracker shield with a per-session blocked-request count
- Privacy dashboard and clear-session action
- Find in page, focus reading style, Hindi translation shortcut, data saver and page text sizing
- Copy link, open current page in another app, and page/connection information
- Desktop-site request, page sharing and native tab recovery after WebView renderer failure
- GitHub Actions workflow to build a debug APK

## Privacy behaviour — read carefully

- The app does not create or store a browsing-history database. A WebView tab has an in-memory back/forward list only while it remains open.
- App backup is disabled; WebView form/password saving is disabled; Android autofill is disabled for browser WebViews.
- Cookies and website storage may exist temporarily while the app is running so sites can function. They are cleared when the app exits, when switching privacy modes, when using Clear Session, and at the next startup (including recovery after Android process termination).
- Incognito is a temporary session mode, not a separate operating-system profile. Some sites may not work normally in Incognito because site storage is restricted.
- Downloads are stored in the device Downloads folder and are not deleted when browser session data is cleared.
- The tracker shield is a small built-in host blocklist. It is not a comprehensive ad blocker or tracker protection system; the blocked count is an estimate of matching subresource requests intercepted by WebView.
- Websites, search engines, internet providers and network administrators may still observe requests sent to them. Incognito does not make a user anonymous. HTTPS protects data in transit but does not make a website trustworthy.

## Build in Android Studio

1. Install Android Studio with Android SDK Platform 35 and JDK 17.
2. Extract this ZIP.
3. In Android Studio choose **Open** and select the extracted `SERVI-Browser-Privacy` folder (the folder containing `settings.gradle.kts`).
4. Let Gradle sync finish.
5. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. The debug APK is normally created at `app/build/outputs/apk/debug/app-debug.apk`.

The project version is 1.3.0 and uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21 and Gradle 8.9. The GitHub Actions workflow provisions JDK 17, Android SDK 35, and Gradle 8.9 automatically.

## Build on GitHub

1. Upload the *contents* of this project folder to the repository root (including the hidden `.github` folder, `app`, and the root Gradle files). Do not upload only the ZIP.
2. Commit the files to `main`.
3. Open **Actions → Build SERVI Android APK**. A push to `main` also triggers the build.
4. When the run succeeds, open it and download **SERVI-Browser-debug-APK** under Artifacts.

## Before public release

Test on multiple physical Android devices and Android System WebView versions. Review the privacy implementation and the tracker blocklist. This starter does not provide VPN, DNS filtering, certificate pinning, a full Chromium engine, complete ad blocking, or independent security auditing. Some sites may block WebView, require unsupported authentication features, or use DRM not provided by the system WebView.
