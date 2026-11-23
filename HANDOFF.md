# Project handoff: custom Android Auto replacement

Living document. **Update it at the end of every work session.** Any model or person picking this
up should read it first. Latest status is at the top of each section.

## Goal
A custom phone-side Android Auto replacement that the owner's **2017 GMC Sierra 1500 Denali
(IntelliLink, wired USB)** displays, running on the owner's **Google Pixel**.

Must have:
- Their own **pretty GUI with switchable themes**
- **YouTube Music controls**: play/pause/skip/seek, now playing. No library browsing needed.
- **Phone** (stock dialer) and **Google Messages**: notifications, read aloud, reply
- **Map**: native in-app map, using Google's map display plus our own turn-by-turn (owner chose this, "option 2")
- **Garage button** on the home screen that opens the door by driving the official **myQ app**
  on the phone through an AccessibilityService. It doesn't use the myQ API.

- **Split-screen layout, up to 3 panes at once**, like Android Auto's dashboard: e.g. the map large,
  a YouTube Music now-playing card, and a notification/call card visible together. The layout
  is the owner's own design, not a copy of Google's.
- **Must feel fast. No lag.** This is a top priority (stock Gearslip felt slow). Treat smoothness as a
  requirement for every feature: keep recomposition cheap, avoid heavy work on the main thread,
  and measure on the truck.

Hard constraints (from the owner, don't re-propose these):
- No extra hardware (no ratgdo etc.), no Home Assistant, no unofficial myQ cloud API.
- Not an add-on tile inside stock Android Auto. It has to be a full replacement.

## Key decisions and why
| Decision | Why |
|---|---|
| Build on **Gearslip** (github.com/twinksoftware/Gearslip, AGPL-3.0) | Proven on the owner's truck 2026-11-23: it displayed over USB. Its protocol/TLS/video/audio/touch stack is the hard part. |
| Use the aasdk-published head-unit-trusted cert (Gearslip "download" option) | That's what worked on the truck. Never commit cert/key files to this repo. |
| Replace Gearslip's car UI at its root composable | The car UI is a Compose tree in a `Presentation` on a `VirtualDisplay` feeding a MediaCodec H.264 encoder (`ScreenProjector.kt`). The root is `CarUi()` in `car/CarUi.kt:111`. |
| Map: our own (Google Maps SDK display + routing + own voice guidance) | Google Maps/Waze car screens only bind to Google-signed hosts. |
| Drop heavy Gearslip extras (browser/adblock, offline speech models, third-party car-app hosting, wireless) | The owner reported Gearslip ran slowly; these are the likely cost. Not yet measured. |

## Repo layout
- `DriveApp/`: **our app ("Dashline")**, a copy of Gearslip at upstream commit `4e3f8e6` with our changes.
  The module is still `gearslip`, and the Kotlin package is still `app.seb3thehacker.gearslip` (renaming 200+ files can wait).
  applicationId is `app.dashline` (debug: `app.dashline.dev`, label "Dashline Dev"). The APK lands in `DriveApp/build/Dashline-debug.apk`.
  - **Our code lives in `gearslip/src/main/java/app/seb3thehacker/gearslip/dash/`**:
    - `DashUi.kt`: root car UI (rail, 3-pane dashboard, music/messages/settings screens, latency test, "classic" fallback to Gearslip's `CarUi`).
    - `DashParts.kt`: `Tap` (acts on touch-DOWN, pressed colour drawn without recomposition), `Card`, `Label`, `GlyphIcon` (Canvas-drawn icons).
    - `DashTheme.kt`: `DashTheme` and `DashThemes` (Carbon, Denali Chrome, Neon Night), plus `DashPrefs` (SharedPreferences "dashline").
    - `PhoneMedia.kt`: MediaSession remote control (YouTube Music etc.) through notification-listener access.
  - `DashUi()` replaced `CarUi()` at all 4 call sites: `GearslipActivity.showCarUi`, `LauncherActivity`, `ui/CarPreviewScreen`, and debug `CarPreviewActivity`.
- `upstream/Gearslip/`: pristine Gearslip clone for diffing (git-ignored).
- `screenshots/`: renders of the car screen from the emulator.
- `STAGE2_TRUCK_TEST.md`: the truck test procedure that proved Gearslip works (done).
- `HANDOFF.md`: this file.

## Dev environment (Windows PC)
- Android Studio installed via winget at `C:\Program Files\Android\Android Studio` (bundled JDK at `...\jbr`, JDK 25).
- Android SDK at `%LOCALAPPDATA%\Android\Sdk` (cmdline-tools/latest, platform-tools). User env vars set: `ANDROID_HOME`, `JAVA_HOME`.
- Gearslip: AGP 9.1.1, Kotlin 2.4.0, Gradle 9.3.1, compileSdk/targetSdk 37, minSdk 31.
- Build: in `DriveApp`, run `gradlew.bat :gearslip:assembleDebug -PnoR8` (fast, about 1 min clean, a few seconds incremental). JAVA_HOME = Android Studio's `jbr`.
- Emulator: AVD `dash36` (android-36-ext19 google_apis x86_64). Start it headless with
  `emulator -avd dash36 -no-window -no-audio -gpu swiftshader_indirect`.
- **Render car screens without the truck**: install the debug APK, then
  `adb shell am start -n app.dashline.dev/app.seb3thehacker.gearslip.CarPreviewActivity --ei w 800 --ei h 480 --ei dpi 160 --es taps "x,y;x,y"`.
  This writes `preview-N.png` (one per tap) to `/sdcard/Android/data/app.dashline.dev/files/`. Grant music access with
  `adb shell cmd notification allow_listener app.dashline.dev/app.seb3thehacker.gearslip.notify.GearslipNotificationListener`.
- The SDK uses the new `android` CLI (`cmdline-tools/latest/bin/android.exe sdk install ...`). `sdkmanager` is deprecated.

## Progress log
- 2026-11-23: Explored options. Proved with the stock Gearslip APK that a non-Google app can project to the truck (it also ran Vivi Music + Vela Maps). Installed the toolchain, cloned Gearslip, mapped its architecture. Unmodified Gearslip builds on this PC
  (`upstream/Gearslip/build/Gearslip-debug.apk`, about 4.5 min clean build). Not yet installed on the Pixel. The debug build is
  signed differently from the release Gearslip already on the phone, so the release app must be uninstalled first.
  Got the truck log (see Known issues).
- 2026-11-23: Created Dashline (`DriveApp/`), committed the Gearslip baseline in git, and built the first Dashline UI: rail, a 3-pane dashboard
  (map placeholder with clock, YouTube Music card, call/notification card), music screen, messages list, theme picker
  with 3 themes, touch latency test screen, and the classic Gearslip UI as a fallback. Verified by rendering in the emulator through the
  real projection path. **Not yet tested on the truck.**
- 2026-11-23: Installed Dashline Dev on the owner's Pixel 10 Pro XL. Release Gearslip was **uninstalled**
  (permission conflict). Granted notification listener + phone/contacts/call-log/mic/BT permissions with adb. Rendered on the
  Pixel with `CarPreviewActivity`: the **live YouTube Music session showed correctly** (title, artist, art, progress), and real
  notifications showed. The phone has myQ (`com.chamberlain.android.liftmaster.myq`) and YouTube Music installed;
  Google Android Auto (`gearhead`) is disabled. Next: owner runs `TRUCK_TEST_DASHLINE_1.md`.

## Known issues / risks
- **Gearslip ran slowly on the truck.** The cause is unknown. Video settings look sane (HW H.264 Baseline at negotiated size, 800x480@30 = 8 Mbps). Suspects: extra features, Compose recomposition, encoder repeat-frame behavior. Get a Gearslip session log from the truck to confirm.
- **Touch lag on the radio** (owner report): a big delay between tapping the radio and the radio
  showing a response. The phone itself is fast. Path: HU touch → USB → phone input → Compose →
  VirtualDisplay → H.264 encoder → ack-gated send → HU decoder → screen. Suspects, in likely order:
  1. HU decoder holds each frame until the next arrives. Gearslip works around this with
     `KEY_REPEAT_PREVIOUS_FRAME_AFTER` = 100 ms (`VideoSource.kt`), which adds up to ~100 ms. Fix options:
     rewrite the SPS VUI with `bitstream_restriction` `max_dec_frame_buffering=0` / `num_reorder_frames=0`;
     shorten the repeat to ~1 frame; push a duplicate frame immediately after any input-driven change.
  2. Ack flow control: `sendVideoFrame` in `GearslipRunner.kt` blocks while `inFlight >= max_unacked`.
     A HU with a small window and slow acks makes every frame wait a full round trip. The log reports `ackWaitMs`.
  3. Compose `clickable` fires on finger *up*, so the response waits for the HU's up event. Use
     press-state feedback on *down* for car buttons.
  4. Heavy recomposition / extra features on the main thread.
  Gearslip already logs `touch -> frame: Xms (draw, encode and wait)` and the ack wait per session.

  **Measured 2026-11-23** (owner's truck log, Gearslip 0.4.0-alpha, Pixel 10 Pro XL, Android 17):
  - Head unit: "GMC Intellilink" 2017, firmware `GMG3G_LINUX_16.3B020`, AA protocol 1.3, video 800x480@30
    H.264 BP, density 160, **max_unacked=4**, touchscreen 480x800 (rotated coords), touch moves at ~20 Hz.
    The HU cert is General Motors, issued by Google Automotive Link. Music goes over Bluetooth (HU keeps its own source).
  - Phone side **touch→frame-sent: usually 12–55 ms, worst 131 ms** (draw 2–81 ms, encode 9–50 ms).
  - **Ack wait is about 0 ms** (acks return in about 2 ms; inFlight never above 1). Suspect #2 is ruled out.
  - The Pixel encoder's SPS already has VUI `bitstream_restriction` with `num_reorder_frames=0`,
    `max_dec_frame_buffering=1`, so suspect #1 is mostly covered on the encoder side.
  - Stream runs ~5 fps when still (repeat frames) and only 8–17 fps during interaction (not 30).
  - **Conclusion:** the phone→USB path costs only ~40 ms. The felt lag is most likely (a) UI design: actions fire
    on finger-up, Compose's ~150 ms press delay inside scrollables, screen transition animations shown at
    low fps, and (b) the HU's own touch-report and decode/display time, which can't be seen from the phone.
  - Next: build a latency test screen (full-screen flash on touch-DOWN) and have the owner film it in
    slow-mo to measure true end-to-end time. Design the UI to act on touch-down with no transition animations.
- **USB transport dropped** after 149 s in that session (`read failed: EIO`, then the accessory detached).
  Cause unknown: cable, port, or the HU resetting. If it repeats, try another cable and compare logs.
- The dashboard alert card shows any app's notification (e.g. Redfin). Consider limiting it to messaging apps plus
  an allow-list setting.
- Google's Android Auto (`com.google.android.projection.gearhead`) must be disabled or it grabs the USB accessory. Undo: `adb shell pm enable com.google.android.projection.gearhead`.
- Gearslip is alpha. An upstream change can break cars that worked before. Pin to a known-good commit.
- myQ AccessibilityService automation will break if myQ changes its UI. Plan a user-run "re-learn the button" setup.
- Google Maps SDK needs an API key (owner's Google Cloud project). The Routes API is billed past its free tier.
- AGPL: fine for personal use. Distributing the app requires releasing its source.

## To do (in order)
1. [ ] Get unmodified Gearslip building on this PC; install on the Pixel via adb; confirm it still works on the truck.
2. [x] (done 2026-11-23, see Known issues) Collect a Gearslip session log from the truck (tap around for a minute) to measure touch→frame
   latency, ack wait, and the HU's max_unacked. Then fix the latency at the protocol/encoder level
   first, since every feature depends on it.
3. [ ] Create our app (new package/name) from Gearslip, keeping the protocol/, audio/, call/, notify/, media/, mirror/ and root runner files. Strip browser/, speech/, wireless/, update/, host/ (car-app hosting).
4. [x] (first version) New root composable + theme system (theme JSON: colors, fonts, wallpaper, tile layout), with 3 starter themes.
5. [ ] Dashboard: a split layout of up to 3 panes (a primary pane such as the Map, plus side cards for Music
   and notifications/calls). The panes can be swapped, there's a full-screen mode per app, and a launcher
   with tiles for Map, Music, Phone, Messages, Garage, plus a clock/weather strip. Each pane recomposes independently.
6. [x] (first version, untested with YTM on a real phone) Music: MediaSession controller via NotificationListener (YouTube Music).
7. [ ] Phone + Messages: reuse Gearslip's call/ and notify/ code, restyled.
8. [ ] Garage: AccessibilityService that opens myQ, finds the door button (by learned view id/text), taps it, returns. Includes a setup screen.
9. [ ] Map: Google Maps SDK in the Presentation, destination search, routing, voice guidance.
10. [ ] Install Dashline on the Pixel (uninstall release Gearslip first: both declare the
   `android.car.permission.TEMPLATE_RENDERER` permission with different signing keys). Run the latency test in the truck with slow-mo video.
11. [ ] Strip unused Gearslip weight: the speech engine warms ~900 MB of models in `CarServices.init` (Dashline doesn't call it,
   except when the classic UI is opened), plus browser/adblock, the update checker, and stats.
12. [ ] Phone screen (recents/favourites/keypad), message reply, Garage, Map (see above).
13. [ ] Performance pass: measure frame times and encoder output on the truck.
