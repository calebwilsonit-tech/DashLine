# Dashline

A phone-side Android Auto replacement with its own look: a themeable 3-pane dashboard,
YouTube Music controls, calls and texts, maps with live traffic, a voice assistant, and a
one-touch garage door button. Everything runs on the phone and shows on a factory car screen
over the normal Android Auto USB connection.

Built and tested on a 2017 GMC Sierra (IntelliLink) with a Google Pixel.

## What it does

- **Dashboard**: map, now playing, and the newest message or call side by side. Three themes.
- **Maps**: search, routes with live traffic and spoken turn-by-turn on the car's speakers.
- **Music**: controls for whatever the phone plays (YouTube Music and others), and starts it when the car connects.
- **Messages**: chat apps only, with Reply by voice, Read aloud, Mark read and Mute.
- **Contacts**: favorites and A-Z, with Call and voice Text.
- **Voice**: the phone's own Google Assistant / Gemini from the radio's mic button or the steering-wheel voice key, with its words shown on the car screen. An optional built-in assistant uses your own Gemini API key.
- **Garage**: hold a button on the car screen to open or close the door through the official myQ app on the phone.
- **Updates**: checks this repository's releases and installs new versions.

## Built on

- [Gearslip](https://github.com/twinksoftware/gearslip) (AGPL-3.0) for the Android Auto protocol, video, audio and touch. Dashline started as a copy of it.
- [Vela](https://github.com/PimpinPumpkin/Vela) (GPL-3.0) for maps: its `:core` engine is included as a prebuilt library (`DriveApp/vela/`).
- [MapLibre](https://maplibre.org/) with [OpenFreeMap](https://openfreemap.org/) tiles (OpenStreetMap data).

Dashline is licensed AGPL-3.0, like Gearslip. See [LICENSE](LICENSE).

## Install

Download the APK from [Releases](https://github.com/calebwilsonit-tech/DashLine/releases), then:

1. Turn off Google's Android Auto app on the phone (it claims the USB connection first).
2. Open Dashline, follow its setup, and plug into the car.
3. Grant notification access (music and messages) and, for the garage button, turn on
   "Dashline garage button" in Accessibility.

It uses a certificate published by the open-source aasdk project, which some newer cars reject.

## Building

See [HANDOFF.md](HANDOFF.md) for the toolchain, the debug harness, and how to rebuild the Vela library.

```
cd DriveApp
./gradlew :gearslip:assembleDebug -PnoR8
```

## Not affiliated

Dashline is not made or endorsed by Google, General Motors, Chamberlain or any carmaker. Use it
only while parked or hands-free; keep your eyes on the road.
