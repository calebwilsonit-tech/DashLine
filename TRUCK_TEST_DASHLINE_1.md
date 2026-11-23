# Truck test #1: Dashline first build

Gearslip has been uninstalled from the Pixel and **Dashline Dev** is installed.
Google's Android Auto is still turned off.

## Before you go (on the phone, about 2 min)
1. Unplug from the PC.
2. Open **Dashline Dev**. It's Gearslip's setup underneath, so it asks for the **certificate** again.
   Pick the **download** option, same as before. Skip anything it asks that you don't want.
   Music, notification and phone permissions are already granted.

## In the truck
3. Plug in the same way as before. If the phone asks which app to open, choose **Dashline Dev**.
4. Try out:
   - **Dashboard:** start YouTube Music on the phone, then use play/pause/skip on the radio.
   - **Rail buttons** (Home, Map, Music, Phone, Messages, Garage, Settings) and the radio's own hard buttons.
   - **Settings → Theme:** try all three.
   - **How it *feels*:** is tapping faster than Gearslip was?
5. **Latency test** (Settings → Touch latency test):
   - Set your phone or a second phone to **slow-motion video** (240 fps is best).
   - Film the radio screen with your finger in view. Tap the screen about 10 times, with a pause between taps.
   - Each tap flips the screen black↔white the moment the radio sends the touch to the phone.
   - Send me the video, or count the slow-mo frames yourself from fingertip contact to the flip.

## Garage (new)
- With the phone **screen off**, connected to the truck (Extend Unlock trusted device), **hold** the garage strip
  under the clock on the dashboard for about half a second. The phone screen should light up briefly, myQ opens, the door moves,
  and the phone goes dark again.
- **Then try it a second time.** This checks whether putting the phone back to sleep paused Extend Unlock. If the second
  press says "Phone is locked", tell me and I'll change how it goes back to sleep.

## Afterwards
6. Send me the log: **Dashline Dev → Logs → Earlier →** the truck session **→ Share**.
   Save it to the project folder, or attach it.
7. Tell me what felt slow, ugly, or wrong.

## Known gaps in this build
Map and the Phone keypad/recents are placeholders. Message replies aren't in Dashline yet.
"Gearslip classic UI" in Settings still has all of Gearslip's features if you need them.
