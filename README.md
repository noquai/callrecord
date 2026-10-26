# Call Record

A small Android app that automatically records phone calls. It was built for the
**Motorola Edge 60 Fusion** (Android 15) and should work on most Android 10+ phones.

## How it works (and its limits)

Since Android 10, Google has blocked regular apps from recording call audio directly.
Only system apps may use the `VOICE_CALL` audio source. This app works the same way
Cube ACR and similar apps do:

- An **accessibility service** listens for call state changes. Android lets an enabled
  accessibility service use the microphone while a call is active.
- When a call connects, it records the microphone to an `.m4a` file. When the call
  ends, it reads the call log to find the number and saves the file.

**What this means for you:**

- Your voice is always recorded clearly.
- **The other person is only recorded clearly when you use speakerphone.** On the
  earpiece they will be faint or silent.
- If you get no audio or a silent file, change the **Audio source** setting in the app
  (try *Microphone*, then *Voice communication*).
- Google Play doesn't allow apps like this, so you install the APK yourself.

> **Alternative:** in India, the Edge 60 Fusion's built-in Google Phone app can record
> both sides of a call (Phone app → Settings → Call recording). It announces the
> recording to the other person. If that's acceptable, it gives better audio than any
> third-party app can.

**Legal:** call-recording laws differ by country. Make sure you have the other
person's consent where the law requires it.

## Get the APK

Every push builds the app on GitHub Actions:

1. Open the repository's **Actions** tab and click the latest **Build APK** run.
2. Download the **callrecord-debug-apk** artifact (a zip) and extract `app-debug.apk`.
3. Copy it to the phone and open it. Allow "Install unknown apps" for your file manager
   or browser when Android asks.

To build it yourself, install Android Studio (or the Android SDK + JDK 17) and run:

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Set up on the phone

Open **Call Record** and work through the setup list:

1. **Grant permissions**: microphone, phone, call log, contacts, notifications.
2. **Allow restricted settings**: Android 13+ hides accessibility for apps installed
   outside Play. Go to *Settings → Apps → Call Record → ⋮ (top-right) → Allow
   restricted settings*. If the menu item isn't there, try step 3 once first; Android
   shows the option after it blocks you.
3. **Turn on the recorder service**: *Settings → Accessibility → Call Record* → On.
4. **Disable battery optimisation** so Android doesn't kill the service.

Then make a test call on speakerphone and check that it appears in the list. You can
play, share (WhatsApp, Drive, email…) or delete each recording.

Recordings are stored in `Android/data/com.noqu.callrecord/files/Recordings/` and are
named like `20261026_143005_IN_+919876543210.m4a`.

## Project layout

| File | Purpose |
| --- | --- |
| `CallRecorderService.kt` | Accessibility service: watches call state, runs `MediaRecorder`, names files using the call log |
| `MainActivity.kt` | Setup checklist, settings, and the recordings list with play/share/delete |
| `Recordings.kt` | File naming and listing |
| `Prefs.kt` | Settings (auto-record on/off, audio source) |
