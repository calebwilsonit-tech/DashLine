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
    - `Garage.kt`: the garage button. `Garage` state machine, plus `GarageService` (an AccessibilityService limited to myQ
      by `res/xml/dash_garage_service.xml`), plus `GarageWakeActivity` (turns the screen on and calls `requestDismissKeyguard`).
      Flow: wake the phone, dismiss the keyguard (works without a PIN only under a trusted-device unlock), launch myQ, poll for
      `device_state_icon` until it's on screen and stable, `ACTION_CLICK` (falls back to a gesture tap), read `device_status`,
      go HOME, and LOCK_SCREEN if the phone was dark before. 12 s timeout. myQ 5.325 tags: `device_name`, `device_status`,
      `device_state_icon` (Compose testTags exposed as bare view ids).
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
- 2026-11-23: **Maps, rebrand, updater, publishing.**
  - **Maps = Vela's `:core` engine + Dashline's own map UI.** Vela (github.com/PimpinPumpkin/Vela, GPL-3.0, compatible with
    AGPL-3.0) is cloned in `upstream/Vela` (git-ignored). Its `:core` is built with Vela's own toolchain
    (`cd upstream/Vela && ./gradlew :core:assembleRelease :osmand-shaded:shadowJar`; it needs the 4 OsmAnd jars from
    Vela's `obf-runtime` release in `core/libs/`, a `local.properties`, and the `java { toolchain 17 }` block removed from
    `osmand-shaded/build.gradle.kts` because only JDK 25 is installed). Outputs are copied to `DriveApp/vela/vela-core.aar` +
    `osmand-java-relocated.jar` (the AAR already bundles osmand-shared-jvm, gnu-trove and kxml2; adding those again
    gives duplicate classes). Built from Vela commit 09f9d84.
  - `dash/DashNav.kt` hand-wires the objects Vela's Hilt graph would: `CoreModule.okHttpClient()`, `CalibrationStore`
    (refreshed at start from Vela's GitHub), `GoogleSession`, `JsTransforms`, `DiagLog`, `CoreModule.routeEngine`,
    `GoogleMapsDataSource`, `VoiceGuide`, `Haptics`, and `NavSession`. GPS goes from LocationManager into `NavSession.onLocation`.
    Vela's voice is `muted` and its `onMuted` lines go to Gearslip `DirectionsVoice.speak` (new public hook), which plays them on the
    car's guidance channel. No Cronet (Vela uses it for Google requests when present), so OkHttp only.
  - `dash/DashMap.kt`: one MapLibre `MapView` (textureMode, OpenFreeMap liberty/dark) kept in `DashMapHolder` and
    re-parented between the dashboard pane and the Map screen. It has route and position GeoJSON layers, a follow camera, a turn card
    and trip bar, voice "Where to?", results, and a route preview with Go. The assistant has a `navigate_to` tool plus an
    offline "navigate to X" fallback. Verified on the Pixel: the search returned 8 results, the route had 23 steps, and both render on the car frame.
    **Not tested yet: a real drive with turn-by-turn.**
  - Rebrand: `rebrand.py` swapped the standalone word "Gearslip" in strings (all locales), manifest labels and Kotlin
    string literals (1,812 replacements). Internal names, packages, log files and URLs are untouched. The radio's AA icon label
    is now "Dashline". About credits Gearslip and Vela. Gearslip's donation rows and prompt are hidden
    (`DASHLINE_HIDE_GEARSLIP_DONATIONS`), and its chat and translation rows were removed.
  - Updater: Gearslip's `UpdateChecker` now reads `api.github.com/repos/calebwilsonit-tech/DashLine/releases`. Versions come
    from git tags `vX.Y.Z[-beta|-alpha]` (`gitVersion` in `gearslip/build.gradle.kts`). It needs release-signed APKs uploaded as
    release assets (not `*debug*`), signed with one key forever (`keystore.properties`, never committed).
  - **Releases:** `scripts/release.sh <version> [notes]` tags `v<version>`, builds the signed release APK, pushes, and creates the GitHub
    release with `Dashline-<version>.apk` (a `-beta`/`-alpha` suffix makes a pre-release). The signing key is
    `C:\Users\wiked\.dashline-signing\dashline-release.jks` with its `keystore.properties` (copied into `DriveApp/`, git-ignored).
    **Back that folder up: losing it means users must uninstall to update.** Release builds have R8 off (Vela reflection).
    The debug build is also signed with this key when it's present, so installs signed with the old debug key must be uninstalled once.
  - Repo: public at github.com/calebwilsonit-tech/DashLine (branch `main`). Before the first push, history was rewritten to drop the
    phone screenshots (`screenshots/pixel/`) and the phone's adb serial. Never commit phone screenshots that show notifications or locations.
  - Messages filter tightened: chat apps or anything with a reply action; Facebook/Twitter/Reddit excluded.
- 2026-11-23: **Full-drive log (`Gearslip log-3.txt`, keyframe build).** Three sessions, 393 s / 197 s / ~230 s.
  - All-keyframes confirmed in the field: `key=` equaled `sent` all drive, frames ~31–36 KB, ~10 fps on a still screen, acks immediate,
    phone-side touch→frame median 47 ms (p90 82, max 134).
  - **Every session ended the same way:** the car took its screen (VideoFocus mode=2, e.g. a radio button), the driver came back (mode=1),
    Gearslip sent Start + 1 frame (or none), the encoder produced nothing more (still screen), and **~2.5–3 s later the HU reset USB (EIO)**.
    Fix: **video keep-alive** in `GearslipRunner` (`startKeepAlive`/`resendLastKeyFrame`). In ALL_KEYFRAMES mode it resends the last keyframe
    whenever nothing has gone out for 200 ms, and immediately on hand-back. **Untested on the truck.**
  - Garage: the first press worked (1.7 s). Later presses said "Phone is locked" although the truck is a trusted device. Cause: the old build's
    `GLOBAL_ACTION_LOCK_SCREEN` after a press counts as a manual lock, which pauses Extend Unlock. Removed. Plus a new fallback:
    `Garage.lockedOut` waits up to 30 s for `ACTION_USER_PRESENT` ("Unlock your phone to finish") and then carries on.
  - Audio was still Bluetooth in this drive: the audio-fix build wasn't installed yet. It is now.
  - Phone assistant: `GarageWakeActivity` no longer waits on keyguard dismissal for the assistant (it hung waiting for a fingerprint).
    Verified that the assistant window (`googlequicksearchbox/...voice.robin.main.MainActivity`) opens from the radio mic. Bar text capture with
    real speech is **not verified yet**; Gemini closes itself after a few seconds of silence.
- 2026-11-23: **Owner prefers the phone's own Google Assistant/Gemini** (the power-button one) with a bar on the radio.
  `dash/PhoneAssistant.kt`: the mic button and the steering-wheel voice key (`CarKeys.voice`) call `GarageWakeActivity` (FOR_ASSISTANT: screen on,
  try keyguard dismiss) and then `ACTION_VOICE_COMMAND` (falling back to `ACTION_ASSIST`), started through the helper accessibility service.
  The helper's config now also covers `com.google.android.googlequicksearchbox` and `com.google.android.apps.bard`. Their window text is read
  into `PhoneAssistant.state.lines` and shown by `PhoneAssistantBar` at the bottom of the radio (Close = GLOBAL_ACTION_BACK). The bar ends when
  the assistant window goes away, after 6 s with no window, or after 90 s. Android can't put the real panel on the car display.
  **Unknown: whether the assistant's spoken replies reach the truck** (USAGE_ASSISTANT isn't capturable; may play from the phone).
  `DashPrefs.Assistant` = PHONE (default) | DASHLINE (the Gemini-API assistant below), chosen in the phone's "Dashline settings".
  Also fixed: **steering-wheel media keys did nothing** (they went to Gearslip's unused CarMedia); `CarKeys` now falls back to `PhoneMedia`.
  Not tested on the Pixel yet (unplugged). Test: preview harness tap on the mic (38,58), then check the log for "assistant:" and that the bar renders.
- 2026-11-23: **Voice assistant + music auto-play.**
  - `dash/DashAssistant.kt`: Gearslip's `CarAssistant` listens (mic button at the top of the rail, or the steering-wheel voice key)
    and speaks. DashUi collects `CarAssistant.pendingCommand`, then `DashAssistant.handle`, then `CarAssistant.reply`. With a Gemini key it calls
    `generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` (key in the `x-goog-api-key` header) with function
    tools: call_contact, text_contact, play_music, media_control, garage_door, web_search. web_search triggers a second request with
    the `google_search` grounding tool. It keeps the last 6 turns, reset on disconnect. Default model is `gemini-flash-latest`
    (alias, changeable in settings). With no key or no network, a keyword fallback handles call/text/play/pause/next/garage.
  - `dash/DashSettingsActivity.kt`: a second launcher icon, **"Dashline settings"**, where the owner pastes their own AI Studio key
    (stored in private SharedPreferences "dashline", never logged) and toggles auto-play.
  - Auto-play: on DashUi start it waits for `CarAudio.capture == ON` (up to 8 s) and then calls `PhoneMedia.autoPlay`, which plays the last
    session or sends a PLAY media key. `PhoneMedia.playFromSearch` uses YT Music's session `playFromSearch`, or the
    MEDIA_PLAY_FROM_SEARCH intent launched through `Garage.service` (the accessibility service is exempt from background-start limits).
  - `DashText.startWith` handles "text Sam I'm on my way" (straight to read-back and countdown).
  - Debug: `CarPreviewActivity --es ask "call sam"` logs the answer. Verified on the emulator (fallback matched "Sam Rivera").
    **The Gemini path and a real call/text/play from voice aren't tested yet** (no key yet; Pixel unplugged).
- 2026-11-23: **Audio, messaging and contacts.**
  - *Silent truck:* the log said "music goes over Bluetooth: the media channel stays closed". GM radios turn BT music off while
    projecting, and Dashline never called `CarAudio.request()` (Gearslip's CarUi did). Fix: `CarSettings.setPipeAudio(true)` and
    `setSystemSpeech(true)` in `GearslipActivity.onCreate` (decided at connect time) and in DashUi, plus `CarAudio.request()` on DashUi start.
    To skip the per-connection consent dialog: `adb shell appops set app.dashline.dev PROJECT_MEDIA allow`. Gearslip's
    AudioChoiceDialog "Use Bluetooth" button can still set it false until the next launch.
  - *Messages:* `dash/DashMessaging.kt` → `DashMessages.isMessage` = known chat apps (Messages, Messenger, Discord, WhatsApp,
    Telegram, Signal, IG, Snap, Chat, Slack, Teams, GroupMe, Dialer) OR it has a reply action OR MessagingStyle lines. Per-conversation
    mute for the drive (cleared on disconnect). The dashboard card and Messages list have Reply (Gearslip `VoiceReply`), Read,
    Mark read and Mute. List taps fire on finger-up.
  - *Contacts:* `dash/DashPhone.kt` `ContactsScreen` (the Phone rail item) lists favourites, then A–Z with a letter strip; Call
    uses Gearslip `placeCall` (Telecom) and Text uses `DashText` (say → dictate → read back → 4 s countdown → SmsManager).
    New permission `SEND_SMS`, granted with adb. `VoiceOverlay` shows reading/listening/sending with Cancel / Send.
  - Rendered on the emulator; **not yet installed on the Pixel** (it was unplugged), so no truck test yet.
- 2026-11-23: **Found the root cause of the lag** from the owner's latency-test video (`PXL_20261123_223924868.mp4`,
  626 frames at 30 fps, reviewed frame by frame) plus the Dashline log. The radio only updated its picture at frames 88, 239, 388 and 538:
  **exactly every 5.0 s, matching Gearslip's `I_FRAME_INTERVAL_S = 5`**. Between them the spinner was frozen, and flip #3 was never
  shown, although the phone sent 30 fps and every frame was acked. **Conclusion: this IntelliLink displays only keyframes (IDR).**
  Fix: `dash/VideoTuning.kt` with modes ALL_KEYFRAMES (default), KEYFRAME_ON_TOUCH and STOCK, selectable in Dashline
  Settings and applied on the next connect. ALL sets `KEY_I_FRAME_INTERVAL=0` and also calls `requestSyncFrame()` after any non-key output.
  Verified on the Pixel 10 Pro XL encoder: ALL gave 60/60 keyframes, the other modes 1/60. The stats line now logs `key=`.
  **Not yet verified on the truck.** Debug check: `CarPreviewActivity --ez enctest true`.
- 2026-11-23: **First real garage press worked.** From the Pixel's lock screen (unlocked by face/trust, no PIN needed):
  request to myQ accepting the click took 2.5 s, and the door closed (owner confirmed; myQ then read "Closed"). Debug trigger:
  `am start --activity-clear-task -n app.dashline.dev/app.seb3thehacker.gearslip.CarPreviewActivity --es garage dry|press`.
  Known gap: myQ's first frame shows a *stale* status (it said "Open for 49 minutes" right before the press), so the
  status Dashline shows after a press may lag. Consider re-reading myQ a few seconds later, or showing "Sent" until confirmed.
- 2026-11-23: Built the garage button. **Dry run on the owner's Pixel found myQ's door button 0.3 s after the request**
  (no press) and returned home. With the phone locked, it correctly reported "Phone is locked". No real press tested yet.
- 2026-11-23: Installed Dashline Dev on the owner's Pixel 10 Pro XL. Release Gearslip was **uninstalled**
  (permission conflict). Granted notification listener + phone/contacts/call-log/mic/BT permissions with adb. Rendered on the
  Pixel with `CarPreviewActivity`: the **live YouTube Music session showed correctly** (title, artist, art, progress), and real
  notifications showed. The phone has myQ (`com.chamberlain.android.liftmaster.myq`) and YouTube Music installed;
  Google Android Auto (`gearhead`) is disabled. Next: owner runs `TRUCK_TEST_DASHLINE_1.md`.

## Known issues / risks
- **Gearslip ran slowly on the truck.** The cause is unknown. Video settings look sane (HW H.264 Baseline at negotiated size, 800x480@30 = 8 Mbps). Suspects: extra features, Compose recomposition, encoder repeat-frame behavior. Get a Gearslip session log from the truck to confirm.
- **SOLVED (pending truck check): touch lag = the radio shows only keyframes.** See the progress log. The analysis below is kept
  for history; suspects 1–4 were not the main cause.
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
- **USB transport dropped again** in the second Dashline session (74 s, `read failed: EIO`, then the accessory detached). The first time was at 149 s.
  Could be the owner unplugging; ask them. If not, try another cable or port.
- **USB transport dropped** after 149 s in that session (`read failed: EIO`, then the accessory detached).
  Cause unknown: cable, port, or the HU resetting. If it repeats, try another cable and compare logs.
- The dashboard alert card shows any app's notification (e.g. Redfin). Consider limiting it to messaging apps plus
  an allow-list setting.
- **Garage needs the phone unlocked.** A PIN lock can't be dismissed by an app, so the phone must be unlocked or on
  Extend Unlock with the truck's Bluetooth as a trusted device. To verify: whether `GLOBAL_ACTION_LOCK_SCREEN` (used to put the phone
  back to sleep) counts as a manual lock that pauses Extend Unlock. If it does, drop that step and let the screen time out.
- Enabling the garage service with adb: `settings put secure enabled_accessibility_services app.dashline.dev/app.seb3thehacker.gearslip.dash.GarageService`.
  This overwrites the list; the phone had none before. A `force-stop` of Dashline unbinds it; re-put the setting to rebind it.
  The garage UI uses a 600 ms press-and-hold (`HoldButton`) so a bump can't move the door.
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
12. [ ] Phone screen (recents/favourites/keypad), message reply, Map (see above). Garage is done (verify screen-off + Extend Unlock in the truck).
13. [ ] Performance pass: measure frame times and encoder output on the truck.
