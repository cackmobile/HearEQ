# HearEQ

A 10-band equaliser for music streamed from an Android phone to Signia Pure Charge&Go IX hearing aids (ASHA or LE Audio).

It changes the music **before** it's sent to the hearing aids. It does not change the aids' own programs, microphones or the settings your audiologist fitted, and the Signia app keeps working alongside it.

## Features
- 10 bands (31 Hz to 16 kHz), ±12 dB in 0.5 dB steps, plus a pre-amp
- A built-in limiter so boosted bands don't distort
- Presets: Flat, Music, Bass boost, Warm, Vocal clarity, Podcast / speech, Soften treble, plus your own saved presets
- "Only when hearing aids are connected": turns on when the aids connect and pauses when they disconnect
- Works across all apps where the phone allows it. Otherwise it hooks into each music app's playback, or you can pick HearEQ from an app's own Equaliser setting.
- Starts again after a reboot if you left it on
- Needs Android 9 or later

## Build the APK

### Option A: GitHub, from your phone or a computer (no Android Studio needed)
1. Create a new repository on GitHub and upload the contents of this folder (keep the `.github` folder).
2. Open the repo's **Actions** tab. The "Build APK" run starts automatically (or tap **Run workflow**).
3. When it finishes (about 3–5 minutes), open the run and download **HearEQ-apk**. Unzip it to get `app-debug.apk`.

### Option B: Android Studio
1. Open this folder in Android Studio (Ladybug or newer).
2. Plug in your phone with USB debugging on, then press **Run**.
   Or use **Build → Build APK(s)**.

## Install on your phone
1. Open `app-debug.apk` on the phone. Allow "Install unknown apps" for your browser or file manager when it asks.
2. Open HearEQ, allow notifications, then switch **Equaliser on**.
3. Play some music with your hearing aids connected. The status box shows whether it's connected to your aids and to your music apps.

## Troubleshooting
- **Status says "doesn't allow all-apps mode":** some phones (especially Samsung) block global effects. Start playing music first, or look in your music app's settings for "Equaliser" and choose HearEQ.
- **No change in sound:** check that the notification says "Equaliser on". Also check that your aids are the active audio output (Settings → Accessibility → Hearing devices).
- **Two equalisers fighting:** turn off any other equaliser app, and Samsung's "Sound quality and effects", while you use HearEQ.
- **Sounds too loud or distorted:** lower the pre-amp, or use smaller boosts. Your aids already amplify the frequencies you need.
