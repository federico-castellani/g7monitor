# G7 Monitor

An Android app that reads glucose directly from a **Dexcom G7** sensor over Bluetooth, with a modern Jetpack Compose / Material 3 interface. The G7 protocol is ported from [xDrip+](https://github.com/NightscoutFoundation/xDrip). A companion Wear OS app shows the glucose on your watch face and rings the alarms on your wrist.

> ⚠️ **This is not a medical device.** It is a personal, experimental project. Do not make treatment decisions without checking with an approved device.

The app is in English and Italian and follows the phone's language. On Android 13 and later you can also pick a language for the app only, in *Android Settings → Apps → G7 Monitor → Language*.

## Features

- **Sensor**: finds the G7, connects with the 4-digit pairing code, receives a reading every 5 minutes and fills in missed readings automatically (backfill, up to 24 h).
- **Home**: current value, trend arrow, change since the previous reading, time since the last reading, sensor day and expiry, warm-up countdown.
- **Chart**: 3 / 6 / 12 / 24 h with the target range; touch and drag to read single values. Optional line smoothing, reading dots and glucose meter markers (*Settings → Chart*).
- **Statistics**: time in range, average, number of readings.
- **Alarms**: urgent low, low, high, urgent high, falling fast, rising fast, signal loss. Each has its own threshold, snooze time, sound, volume and mode (sound and vibration, vibration only, sound only).
- **Notification** with the latest value and a small chart; optionally the **glucose number in the status bar** instead of the app icon.
- **Home screen widget** with value, arrow and a 3-hour chart.
- **Bluetooth glucose meter** (optional), for meter checks next to the sensor values.
- **Upload** to a [G7 Monitor Server](https://github.com/federico-castellani/g7monitor-server) and/or to **Nightscout**.
- **Follower mode**: another phone can show the same glucose, received from a G7 Monitor Server, with no sensor.
- **Wear OS**: complications for any watch face, and alarms on the watch with a full-screen page and a snooze button.
- mg/dL or mmol/L, adjustable target range, light and dark theme with dynamic colours.

## Requirements

- Android 12 or later, with Bluetooth.
- For the watch: Wear OS 3 or later (Galaxy Watch 4 and newer).

## Installing the app

1. On the phone, open the **[latest release](https://github.com/federico-castellani/g7monitor/releases/latest)** and tap **G7Monitor-X.Y.Z.apk** (the *G7Monitor-Wear* file is for the watch).
2. Open the downloaded file. The first time, Android asks to allow installs from the browser: allow it, go back and tap **Install**.
3. If Android says *"App not installed"* and you already have a G7 Monitor built some other way, uninstall it first (this happens only once, because the signature differs).

**Updates:** the app checks for new versions by itself when you open it. An *Update available* card appears on the home screen (and in *Settings → Updates*): tap **Update** and confirm. The first time, Android asks to allow G7 Monitor to install apps: turn it on, go back and tap Update again. Settings, pairings and readings are kept.

## First setup

1. On first start choose **From the G7 sensor** (or **From the server** to follow someone else's glucose, see [Follower mode](#follower-mode)).
2. **Settings → G7 sensor**: enter the **pairing code** printed on the G7 applicator and tap *Save code*.
3. **Certificate:** nothing to do. On its first pairing with a new phone the G7 asks for an application certificate; the app already includes xDrip's public **"G Key"** ([`G7_keks_QR.png`](G7_keks_QR.png)) and uses it by itself. To use a different one, go to *Settings → G7 sensor → G7 certificate → Use another certificate*: scan its QR, paste its text (`xdp2:…`) or enter the three hex values (`keks_p1`, `keks_p2`, `keks_p3`).

4. **Settings → Background service → Run as a service**, and allow the Bluetooth and notification permissions. The service starts by itself when the phone turns on.
5. In the same page tap **Allow** to remove battery restrictions, so readings keep arriving with the screen off. On Samsung phones also add G7 Monitor to *Battery → Background usage limits → Never sleeping apps*.
6. When the app connects for the first time, Android may ask to **pair** with a `DXCM…` device: accept.

Things to know:
- **Use only one collector on the phone.** If xDrip is installed with G7 collection on, turn it off, or the two apps will fight over the sensor.
- **Other devices** connected to the sensor (pump, receiver) keep working: the app uses the same authentication slot as xDrip on a phone.
- **New sensor:** enter the new pairing code. If the connection gets stuck, *Settings → G7 sensor → Forget sensor* removes the old key and Bluetooth pairing.

## Alarms

Set them in the **Alarms** tab: tap an alarm for its threshold, snooze time, sound, volume and mode.

- They ring on the phone's **alarm** channel, also in silent mode, with a full-screen page on the lock screen.
- **Snooze** silences that alarm for its snooze time. If nobody reacts, it rings again at every new reading while the condition is still true.
- Turning an alarm **off** (or stopping the service) needs the phone to be unlocked (PIN, pattern, password or biometrics).
- The switch at the top pauses **all** alarms for a while, or until you turn them back on.
- **Test alarm** sets one off as if it were real, marked as a test; *Test in 10 s* gives you time to lock the phone.
- The alarms page shows a warning when something would keep an alarm from being heard (notifications off, alarm volume at zero, data collection off).

## Uploading and following

All uploads use HTTPS only. Readings recovered by the backfill are uploaded too.

### G7 Monitor Server

*Settings → Server (uploading)*: enter the address of your [G7 Monitor Server](https://github.com/federico-castellani/g7monitor-server), then an account allowed to upload. The password is used only once: the phone keeps an access code that you can revoke from the website.

- Readings are sent right after they arrive; optionally every 15/30/60 minutes, or on Wi-Fi only.
- **Uploads paused** keeps the connection but holds the readings on the phone; they all go out when you turn it back on.
- When you connect (or reconnect) the server, the phone downloads the last 24 hours from it and uploads everything it has; the server ignores duplicates.

### Nightscout

*Settings → Nightscout*: enter your Nightscout address and either the site's **API_SECRET** or an **access token** allowed to upload (created in Nightscout's *Admin tools*). The app checks them before saving.

- Sensor readings are sent as SGV entries with their trend arrow; glucose meter results as *BG Check* treatments.
- On connecting, the last 24 hours are sent; then every new reading. Older history is not sent, so it does not duplicate what another uploader (such as xDrip) may already have put there.
- It works together with the G7 Monitor Server or on its own, and can be paused without forgetting the secret.

### Follower mode

Choose **From the server** on first start, or later in *Settings → Data source*. The phone then receives the glucose from a G7 Monitor Server with no sensor, with the same home screen, chart, notification, widget, alarms and watch. A **read-only** account is enough. The phone keeps a live connection with the server when it can, otherwise it asks only when a new reading is due (about every 5 minutes).

## Glucose meter

*Settings → Glucose meter* pairs a Bluetooth meter that uses the standard Bluetooth "Glucose" profile: Contour Next One, Accu-Chek Guide and Instant, OneTouch Verio Flex and Select Plus Flex, CareSens and others.

1. Put the meter in pairing mode (usually *Settings → Bluetooth / Wireless → Pair*; on the Contour Next One hold OK until the Bluetooth symbol flashes).
2. Tap **Search** and choose the meter.
3. Enter the PIN shown on the meter's display.

After every measurement the meter sends the result to the phone by itself within a few seconds. Results appear as purple diamonds on the chart, with the difference from the sensor at the same time. They are uploaded to the server and to Nightscout, and they never set off alarms. Control solution tests are ignored, and a wrong meter clock is corrected.

## Display options

- **Settings → Display**: mg/dL or mmol/L, target range, and **Glucose in the status bar** (off by default). When on, the status bar shows the current value instead of the app icon; the icon comes back after 15 minutes without readings.
- **Settings → Chart**: line smoothing (*None*: straight lines; *Light*: a soft curve through every reading; *Strong*: a curve on the average of about 20 minutes), reading dots, glucose meter markers. The chart range chosen on the home screen is remembered.

## Wear OS watch

The watch app receives the glucose from the phone; it works in both sensor and follower mode.

### Complications

| Complication | Types | Shows |
|---|---|---|
| **Glucose** | short text, long text, ranged value | large `123` with the trend arrow as an icon; `123 mg/dL ↗ +4`; a gauge from 40 to 300 mg/dL |
| **Glucose: change** | short text | `+4` since the previous reading, with the minutes since the last reading (`3m`) |
| **Glucose: last reading** | short text | minutes since the last reading |

To add one: long-press the watch face → *Customise* → tap a complication slot → *G7 Monitor*. Tapping a complication opens the glucose screen on the watch.

Battery: the watch never asks for data (the phone sends each reading), the watch face counts the minutes by itself, and after 15 minutes without readings the value turns into `---` without waking the watch.

### Alarms on the watch

When an alarm rings on the phone, the watch shows a full-screen page with the alarm and the value, and vibrates. **Snooze** on the watch silences the alarm on the phone too. The page closes by itself when the alarm stops on the phone.

### Installing on the watch

A computer is needed **only the first time**; after that the watch app updates itself. On the phone, *Settings → Watch* shows whether the watch has G7 Monitor and whether it is up to date.

1. On the watch: *Settings → About watch → Software information*, tap *Software version* 5 times. Then in *Developer options* turn on *ADB debugging* and *Wireless debugging* (watch and computer on the same Wi-Fi) and tap *Pair new device*.
2. On the computer, download and run the install script. It finds or downloads `adb`, downloads the latest watch app and installs it, asking only for the address and code shown on the watch:
   - Windows: [`tools/installa-orologio.ps1`](tools/installa-orologio.ps1), then `powershell -ExecutionPolicy Bypass -File .\installa-orologio.ps1`
   - Mac/Linux: [`tools/installa-orologio.sh`](tools/installa-orologio.sh), then `sh installa-orologio.sh`
3. Open G7 Monitor on the watch and allow notifications. You can turn *Wireless debugging* off.

**Updates:** when the phone app is updated, a notification appears on the watch; tap it and confirm. The watch downloads the new version from the same [release](https://github.com/federico-castellani/g7monitor/releases/latest). The phone and the watch must have the **same signature**: use the APKs from the releases for both.

## Troubleshooting

- **No readings:** open **Diagnostics** (icon at the top of the home screen). It shows whether the G7 is visible nearby, the connection status and, with *Show event log*, every step of the connection. *Copy log* lets you share it.
- **"Certificate refused by the sensor":** import another certificate in *Settings → G7 sensor → G7 certificate → Use another certificate*.
- **"Authentication failed 3 times":** check the pairing code; the app waits 10 minutes before trying that sensor again.
- **Readings stop with the screen off:** remove the battery restrictions (*Settings → Background service*).
- **Server or Nightscout errors** are shown in their settings page and in the event log; the app retries by itself, waiting longer after each failure (up to one hour).

## Building from source

Requirements: a recent Android Studio with **JDK 21** as the Gradle JDK. Open the project folder, wait for the Gradle sync and run the `app` configuration on a phone with USB debugging, or run `gradlew assembleDebug`. Unit tests: `gradlew testDebugUnitTest`.

Translations live in `res/values/strings.xml` (English, the default) and `res/values-it/strings.xml` (Italian), for both the app and the watch. To add a language, add a `values-xx` folder with the same keys.

## License

GPL-3.0 (see `LICENSE`). The G7 protocol is a port of xDrip+ code (© JamOrHam and contributors, GPLv3), so this project is GPLv3 too.
