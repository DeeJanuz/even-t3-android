# Even Phone Companion

A small Android companion that forwards selected replyable notifications to [T3 Code Assistant for Even Realities G2](https://github.com/DeeJanuz/t3-code-assistant-bridge). Dictate and review replies on the glasses, then hand them back to the originating Android app.

**Public beta:** 0.1.3-beta.1. Android 9+ is required. This is an independent community project, not an official Even Realities or messaging-app integration.

## Install the beta

1. Set up the computer bridge using the [copyable installer skill](https://raw.githubusercontent.com/DeeJanuz/t3-code-assistant-bridge/main/skills/t3-bridge-deploy/SKILL.md) in your existing T3 Code agent. It handles Windows, macOS, or Linux prerequisites and startup, and provides your phone setup details.
2. Install Tailscale on the phone and sign in to the computer’s tailnet.
3. Download the signed APK and `SHA256SUMS` from [Releases](https://github.com/DeeJanuz/even-t3-android/releases). Open the APK on Android and allow installation from the app opening it. Release upgrades keep the same signing certificate. A debug build uses a different key and cannot be upgraded in place to release.
4. Open **Even Phone Companion** and enable **Notification access**. On Android versions that restrict sideloaded apps, open Android Settings → Apps → Even Phone Companion → overflow menu → **Allow restricted settings** first, if that option is shown.
5. In the T3 Code Assistant settings inside Even Hub, use **Android notifications → Pair Android companion**. Enter the resulting short-lived code and your **HTTPS bridge origin** in the Android companion, then pair.
6. Enable the apps you want to forward in Even Hub’s app list. Every app starts disabled. Keep the private network connected; if your phone manufacturer suspends background apps, adjust the companion’s battery/background setting.

The Android URL is an **origin**, for example `https://YOUR-COMPUTER.YOUR-TAILNET.ts.net:8443`. The Even Hub WebSocket field uses `wss://YOUR-COMPUTER.YOUR-TAILNET.ts.net:8443/v1/events`. Use the exact values supplied by your installer, not these placeholders. Release builds reject HTTP and do not bypass TLS certificate checks.

No messaging-account login is required. Signal, Messages, WhatsApp, Gmail, and other apps work only when a notification supplies a compatible, unambiguous text-reply action. Read-only notifications, group summaries, work-profile notifications, and locked authentication-required actions are excluded. Configure the originating app to offer Reply if necessary.

## Behavior and privacy

Single tap selects, double tap returns, and tap-and-hold opens the OS menu. To dismiss without replying, open the notification reader’s menu and select **Dismiss notification**. Long press alone does not dismiss it.

Removing a notification cancels its unfinished glasses reply. Connection loss discards unfinished replies; actions are not queued for automatic replay. A successful Android handoff means the messaging app accepted the action, not that the recipient received it.

Pairing credentials and the replay-prevention journal use Android Keystore encryption. App data is excluded from backup and device transfer. Notification and reply contents are not persisted or logged. The enabled-app inventory is shared with your paired bridge so you can manage forwarding. Android notification races and manufacturer background restrictions still require testing on your phone.

## Build from source

Use the checked-in Gradle wrappers with JDK 17, Android platform 35, and Build Tools 35.0.0. Build and release helpers are in `scripts/`; the computer installer skill can install these developer prerequisites if you want to build the APK yourself. Installing the published APK does not require an Android SDK on your computer.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
```

On Windows use `gradlew.bat`. For a signed release, run `python3 scripts/build_release.py` (`py -3 scripts/build_release.py` on Windows); `--help` explains the external signing-file format. The helper verifies the APK and writes it with `SHA256SUMS` to `artifacts/`. Release signing credentials are external to this repository. Forks use their own signing key and cannot replace a maintainer-signed installation without uninstalling it first. Preserve and securely back up your release key for future upgrades; never commit keys, signing properties, or pairing credentials.

The release build enables code/resource shrinking and excludes all debug notification hooks. Continuous integration runs checks without access to the maintainer’s signing key. Device smoke tests complement these checks; neither emulator tests nor a successful build establish compatibility with every messaging app or phone.

## License

Original code is [MIT licensed](LICENSE). Use, modify, redistribute, or sell it while retaining the license notice. Bundled libraries and data retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md) or **Open-source licenses** inside the app.
