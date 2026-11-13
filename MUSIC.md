# Music

Personal Android fork of the [Music Assistant mobile app](https://github.com/music-assistant/mobile-app), maintained by Blake Hawkins. It is not an official Music Assistant build. The original Apache-2.0 license, copyright notices and third-party licenses remain in place.

Why (Blake, Nov 12–13 2026): "fork it and call it music (like I did with immich named photos). Set it up on the pixel and pixel watch… I want widgets and for it to be easy to manage playback from the app and or widget or both."

## Starting point

- Checkout: `/Users/blakehawkins/Documents/music`, alongside the other projects.
- Fork: https://github.com/hblake-xcantech/music (`origin`). `upstream` tracks music-assistant/mobile-app.
- Daily-use branch: `music/main`, based on upstream tag `android-v0.17.0` (`ba003a18`), the latest Android release. Keep `main` tracking upstream, free of personal changes.
- Why that base: the server is Music Assistant 2.10.7 (API schema 65). The app's `LOCAL_SCHEMA_VERSION` is 65, so 0.17.0 matches it with no "server ahead" warning (`.claude/feature-parity-report.md`). Upstream `main` was only six commits ahead (the 0.18.0 version bump and iOS sheet fixes), so a released tag costs nothing. Re-check that constant against the server before moving the base; a 2.11 server needs a newer app.
- Android application ID: `com.xcantech.music`, so it installs beside the stock app (`io.music_assistant.client`). The Kotlin namespace stays upstream's so shared code and rebases keep working.
- Android name: **Music**. Artwork source: `design/music/`.
- Version: `0.17.0-music.1`, version code `18001` (upstream code × 1000 + fork revision). Increment the revision for every installed update, on the phone and the watch.

## What differs from upstream

Everything the fork adds lives in new files; upstream files are touched only where listed.

| What | Where |
|------|-------|
| Identity: id, name, version, icon | `androidApp/build.gradle.kts`, `res/values/strings.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/values/ic_launcher_background.xml` |
| Two fixes the new id needs: the notification-dismiss broadcast and the voice shortcuts named the upstream package literally | `services/MediaNotificationManager.kt`, `res/xml/shortcuts.xml` |
| Home-screen widgets, player picker, watch bridge | `androidApp/src/main/kotlin/io/music_assistant/client/surfaces/`, `res/layout/music_widget_*`, `res/xml/music_widget_*`, `res/values*/music_widget_*`, `res/values/wear.xml` |
| Wiring for the above | `AndroidManifest.xml` (one block), `di/AppModule.kt` (one line), `MyApplication.kt` (`startSurfaces`) |
| Phone ↔ watch wire, one source for both apps | `wire/src`, tests in `wire/test` |
| Pixel Watch app | `wearApp/` (new Gradle module), `settings.gradle.kts`, `gradle/libs.versions.toml` |

Nothing was added to the in-app UI. The app already has the player pager with transport, volume, queue and player switching on every screen, hardware volume keys for the selected remote player, and the media notification; the gap was control from outside the app, which the widgets and the watch fill.

### One hub, one session

`surfaces/SurfaceHub.kt` feeds the widgets and the watch from the app's existing server session. It opens no connection of its own: state comes from `MainDataSource` (the flows the media notification reads) and commands go through `MainDataSource.playerAction`, so a tap on a widget is the same request as a tap in the app. A command that finds the session asleep wakes it with `ServiceClient.requestCommandRecovery`. The snapshot type (`MusicWire.NowPlaying`) is shared by the widgets and the watch.

The player these surfaces control is the app's selected player. Choosing another in a widget's picker or on the watch selects it in the app too.

### Widgets

Both are resizable, follow light/dark and Material You colours, and open the app when tapped outside a button. RemoteViews, not Glance: the app's Compose comes from Compose Multiplatform, and Glance would add a second consumer of the Compose runtime to keep in step on every rebase.

- **Music · Now playing** (default 4×2). Artwork, title, artist, player name, previous / play-pause / next, volume down/up when wide enough. One row high it becomes a strip; narrow or square puts the artwork on top.
- **Music · Controls** (default 4×1, shrinks to 2×1). Previous / play-pause / next, the player name and current track when there is room, volume at full width.
- Tap the player name for the "Play on" list.

How they stay current: the hub pushes every change while the app process is alive and connected. While something plays, the app's own media service keeps the process alive (the hub starts it after a tap, as `MainActivity` does when the app is open), so track changes arrive. When nothing plays, Android may freeze the idle process; the widget then shows the last snapshot until the next tap, the 30-minute widget update, or the next time the app runs. A change made elsewhere while the process is frozen is not shown until then.

### Watch

`wearApp` is a Wear OS app with the same application ID and signing key as the phone app; the Wear OS Data Layer only pairs apps that match. The watch has no route to the server (no Tailscale, no token), so it never talks to it:

- Phone → watch: `WearPublisher` writes the snapshot as a Data Layer item (`/music/state`, artwork as an asset) and the shortcuts (`/music/shortcuts`). Items reach the watch while its app is closed, which is what the tile and complication read.
- Watch → phone: each tap is one request (`/music/cmd`) to `WearBridgeService`, which runs it through the hub and replies with the snapshot.
- On the watch: now playing with artwork, previous / play-pause / next, volume with the crown and with buttons, a list to pick the player and to start one of the home-page recommendations, a tile with the three transport buttons, and a complication (short and long text).

**Without the phone** (out of Bluetooth range, phone off, or the phone app not installed) the watch shows the last snapshot with "Phone not reachable" and its controls do nothing. There is no direct connection to the server from the watch. If the phone is near but cannot reach the server, the watch says so. Wear OS's own media controls also appear on the watch whenever the phone's media notification is up; that is system behaviour, separate from this app.

## Build and install

JDK 21 (`/opt/homebrew/opt/openjdk@21`; JDK 17 also satisfies the Android build) and the Android SDK (platform 37, build-tools 36.0.0).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=~/Library/Android/sdk
./gradlew :androidApp:assembleSelfSignedRelease :wearApp:assembleSelfSignedRelease

adb -s PHONE install -r androidApp/build/outputs/apk/selfSignedRelease/androidApp-arm64-v8a-selfSignedRelease.apk
adb -s WATCH install -r wearApp/build/outputs/apk/selfSignedRelease/wearApp-selfSignedRelease.apk
```

Tests and checks:

```sh
./gradlew :androidApp:testDebugUnitTest :wearApp:testDebugUnitTest :composeApp:testAndroidHostTest
./gradlew :androidApp:detekt :wearApp:detekt
```

**Signing.** Both apps use upstream's `selfSignedRelease` build type, which signs with the Android debug keystore on this Mac (`~/.android/debug.keystore`). That is a stopgap: an update only installs over a build signed by the same file, and the watch only pairs with a phone app signed by it. Follow-up: a release keystore backed up in 1Password (as `Photos Android Release Signing` is for Photos), wired through `androidApp/keystore.properties` and read with `opx`. Moving to it means uninstalling and reinstalling both apps once (and signing in again). Do not commit keystores, passwords, APKs or account details.

The watch APK is not minified (33 MB): it could not be run on a watch yet, and an unminified build removes R8 as a source of surprises.

To roll back, use the stock Music Assistant app; it is untouched.

## Setup on the phone

The fork starts signed out. Do not copy the stock app's data or token into it.

1. Open **Music**. On the connection screen enter the server host and port (8095), TLS off, and connect. The address is not kept in this repository; it is in `MUSIC.local.md` (untracked) and is the same server the stock app uses over Tailscale.
2. Sign in with the Music Assistant login, or choose "Use a token instead" and paste a long-lived token created in the Music Assistant web UI. Home Assistant / OAuth sign-in returns through `musicassistant://auth/callback`, a scheme the stock app also claims, so Android may ask which app to open: choose Music. Password or token login avoids that.
3. Pick the player to control in the app once.
4. Long-press the home screen → Widgets → Music, and add **Now playing** and/or **Controls**. Resize as you like.

## Setup on the watch

1. On the watch: Settings → Developer options → Wireless debugging → pair, then `adb pair` / `adb connect` from the Mac.
2. `adb -s WATCH install -r wearApp/build/outputs/apk/selfSignedRelease/wearApp-selfSignedRelease.apk`
3. Open Music on the watch near the phone. Add the tile (swipe to the end of the tiles → add → Music) and the complication (watch face editor → Music) if wanted.

## Verified and not verified

Verified (Nov 13 2026):

- Builds: both release APKs; `:androidApp` passes through R8.
- Tests: 299 in `:androidApp` (269 upstream + 30 for the fork), 1229 in `:composeApp`, 16 in `:wearApp`; detekt clean on both app modules. The fork's tests cover the wire encoding, the state mapping, the widget breakpoints, and inflate every widget layout at every size (Robolectric).
- Phone (Pixel 10 Pro, Android 17): `com.xcantech.music` installed beside the stock app, which is unchanged; its process starts cleanly from a widget broadcast with no crash; both widget providers are registered with the system.

Not verified, because it needs Blake's sign-in, his eyes on the home screen, or a watch on adb:

- Anything against the live server from the fork: the fork has never been signed in, and no playback or volume command was sent to the real speakers.
- How the widgets look on the Pixel launcher, and their live updates.
- The media-service start after a widget tap while the app is in the background (Android may refuse it; the widget then falls back to the last snapshot).
- The whole watch app on a watch: it compiles and its pure logic is tested, nothing more. That includes the Data Layer pairing, the crown, the tile buttons and the complication.
- The shortcut list: it reads the home-page recommendation rows, untested against server 2.10.7.

## Contributing upstream

Read the **current** upstream [CONTRIBUTING.md](https://github.com/music-assistant/mobile-app/blob/main/docs/CONTRIBUTING.md) and the [OHF AI Policy](https://github.com/music-assistant/.github/blob/main/AI_POLICY.md) before any proposed upstream work. Their rules can change independently of this snapshot.

As checked when this fork was started: AI tools are allowed, but a human must review, understand and be able to explain every change, in their own words, including the pull request description and replies to maintainers. Autonomous agents are not allowed to contribute, and unreviewed AI output is closed. Upstream also asks for an issue first, and for asking in the issue before working on it.

So: nothing in this fork goes upstream by itself. This AI-assisted work must not be submitted as-is or presented as human-authored. For something worth offering (the hard-coded package in `MediaNotificationManager` and `shortcuts.xml` would be a candidate), Blake files or finds the issue, starts a clean branch from current `upstream/main`, reviews and owns the change, and writes the description himself. No automatic upstream PRs, issues or comments.

Keep each future improvement in a focused commit, separate from branding.
