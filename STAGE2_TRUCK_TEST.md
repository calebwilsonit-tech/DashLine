# Stage 2: Can a non-Google app draw on the Sierra's screen?

The goal is a 15-minute test in the truck, with no PC and no coding.

## Why this test
**Gearslip** (https://github.com/twinksoftware/Gearslip) is an open-source Android Auto
replacement. Its car list (CARS.md) shows a **2017 Chevrolet MyLink** working over USB.
A 2017 GMC IntelliLink is a closely related GM unit from the same year.
If Gearslip shows up on your screen, the truck accepts a replacement, and our app can be
built on the same connection code.

Gearslip is alpha software from a small developer, not Google. Install it only if
you're comfortable with that. You can uninstall it any time.

## On the Pixel
1. On the Pixel, open https://github.com/twinksoftware/Gearslip/releases and download
   the newest release APK. Install it. Android will ask you to allow installs from your browser.
2. Open Gearslip. On first launch it asks for a certificate. Pick the
   **download** option (the one the aasdk project publishes).
3. Turn off Google's Android Auto for the test so it doesn't grab the USB connection.
   If the Disable button is grayed out in Settings > Apps > Android Auto, skip this
   step for now and tell me. There's a way to do it from a PC.

## In the truck
4. Start the truck, then plug the Pixel into the **same USB port you use for Android Auto**.
   Use a good data cable.
5. If the phone asks which app to open, choose **Gearslip**.
6. On the radio, tap the Android Auto / phone-projection icon if it doesn't open by itself.

## Report back
- **Works:** Gearslip's home screen appears on the radio. Check whether touch works.
- **Rejected:** a certificate or authentication error appears in Gearslip, or the radio says
  the device isn't supported. Gearslip's Settings has a "Download from a link" option for another
  certificate. Try it if you're comfortable, or just tell me what happened.
- **Stuck / nothing:** note how far it got. A screenshot of Gearslip's log screen is ideal.

## Undo
Re-enable Android Auto in Settings > Apps > Android Auto (or ask me for the PC command),
and uninstall Gearslip if you don't want it.
