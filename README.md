# CarPulse

An open-source, privacy-respecting companion app for connected **Skoda** and **Volkswagen** cars in India. One
app, both brands: it talks to the same telematics servers the official apps use, so you see your car's live
data without ads, tracking, or a cluttered UI.

> Unofficial and independent. Not made by, affiliated with, or endorsed by Škoda Auto, Volkswagen AG, or
> Škoda Auto Volkswagen India. See [Disclaimer](#disclaimer).

CarPulse is built on [**OpenVW**](https://github.com/biswatma/openvw) by biswatma. See [Credits](#credits).

## Which cars

Cars enrolled in **MySkoda Connected** or **My Volkswagen Connect Plus** in **India**, with the plug-in
telematics (OBD) dongle reporting. Both brands share one backend (SAVWIPL), so the data is the same:

| Brand | Status | Models recognised |
|---|---|---|
| Skoda | Tested (Slavia) | Kushaq, Slavia, Kylaq, Kodiaq, Octavia, Superb, Fabia, Rapid |
| Volkswagen | **Not yet tested.** Built from OpenVW's VW support; the new OTP sign-in hasn't been confirmed on a VW account | Taigun, Virtus, Tiguan, T-Roc, Polo, Vento |

Cars are shown with CarPulse's own illustrations (sedan, SUV or hatchback, in your car's colour), not carmaker
photos.

If you own a VW and try it, please open an issue saying whether sign-in and live data work.

## Features

**Sign-in**
- Pick **Skoda** or **Volkswagen**, enter the mobile number registered on your car's account, type the SMS OTP.
  The car list from the server then confirms the brand and finds your car's VIN, model and variant.
- Uses the same Cerence OTP sign-in as the current official Skoda app, which also works for accounts the older
  sign-in rejected ("LoginId is Required").
- Sign-in renews itself before it expires. If the server refuses the renewal, the app asks for a new OTP instead
  of silently showing empty data.

**Live data**
- Instrument cluster: speed, RPM, fuel %, battery voltage, coolant and intake-air temperature, engine load,
  odometer, check-engine (MIL) status and distance since diagnostics reset.
- Real-time **push** updates over the car's telematics hub (SignalR) — values move within seconds while you
  drive, with no polling. A green **Live** chip shows when connected.

**Trips & driving**
- Trip history grouped by day, with start/end places, distance and duration, plus date-range search.
- Driving stats: trips, distance, driver score, top speed, idle time, hard stops, and **measured** real-world
  mileage (km/l) from per-trip fuel use.

**Updates & locate**
- Engine on/off and driving alarms (sudden brake, sharp turn, …) as they happen; your account's alert setup.
- OpenStreetMap-based map (Esri tiles, no API key) with the last-parked location and **Open in Google Maps**.
- **Search near your car** (opens Google Maps): your brand's service centres and showrooms, petrol pumps, parking,
  car wash, tyre shops, or anything you type.
- **Saved workshops**: keep your own service centre / garage with one-tap Directions and Call (on this phone only).

**My car**
- Vehicle health, ownership summary, and a fuel-spend estimate with an editable petrol price.
- Car colour from your account or picked manually; model and variant detected automatically.
- Privacy mode that masks VIN / registration / personal details for screenshots.

**Car alerts** (optional, in My car → Owner info)
- A check about every 15 minutes, even with the app closed: **battery low while parked**, **check-engine light**,
  **engine started between midnight and 5 AM**, **fuel low**, and a heads-up if your sign-in expires. Each alert
  fires once and re-arms only after the condition clears. The same check keeps the home-screen widgets current.

**More than one car**
- Every car on your account (even a Skoda and a VW on one number) with a switcher on Home; each car keeps its own
  brand, data, colour and reg number.

**Demo mode**
- "No account? Try a demo car" on the sign-in screen: a clearly labelled sample Slavia and Taigun, no network.

**App lock**
- Fingerprint, face or your screen lock to open CarPulse (after 30 s away). Hides the app's preview in Recents
  (Android 13+). The Android Auto screen never locks.

**App**
- Light/dark theme that follows your phone (or your choice), foldable/tablet layout, and 5 home-screen widgets
  (fuel, overview, large, last trip, parked) that follow the system theme too.
- **Android Auto** — see below.

## Android Auto

A live, read-only dashboard on the car screen:

- **Live while driving**, with the phone in your pocket: the car screen shares the app's sign-in and live feed.
- **Car's own sensors (optional):** under **⋮ → Use your car's own sensors**, allow speed, fuel and odometer, and
  speed comes straight from the head unit several times a second. Not every car shares these values.
- **Glance view** (default), designed for split screen next to Maps: gauge tiles in priority order (speed, fuel,
  range, engine, battery, RPM) that reflow to the panel width, with short labels when space is tight.
- **Details view**, **Recent trips** and **Car health**.

Sideloaded builds only show up in Android Auto in developer mode: Android Auto settings → tap *Version* 10× →
⋮ → Developer settings → **Unknown sources**.

## Build & run

Requires Android Studio / JDK 17+.

```bash
cd android
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/  (installs as <id>.debug)
./gradlew :app:testDebugUnitTest
```

Release builds for Google Play need an upload key: create `android/keystore.properties` (it's git-ignored)
with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then `./gradlew :app:bundleRelease`.
See [PLAYSTORE.md](PLAYSTORE.md).

## How it works / security notes

- All traffic is **HTTPS** to the carmakers' and Cerence's own servers. CarPulse has no server of its own; your
  data goes only to you and to Skoda/VW/Cerence, as with the official apps.
- The savwconnect API wraps bodies in **DES** with keys baked into the official apps. They're included because
  the client can't talk to the API without them; they are obfuscation, not security. Real authentication is
  your per-account **OTP + token**.
- Tokens live in Android's app-private storage and are **excluded from cloud backup**. Like most apps, that
  storage is readable on a rooted/compromised device.
- Place names for parked spots come from Android's built-in Geocoder, which may send coordinates to Google.
  Map tiles come from Esri. Details: [PRIVACY.md](PRIVACY.md).
- Please don't hammer the login endpoint — the backend rate-limits sign-ins.

## Credits

CarPulse is built on [**OpenVW**](https://github.com/biswatma/openvw) by **biswatma** (MIT): the original app,
the reverse-engineered API client, the live feed, cluster, trips, maps and widgets. Details in
[NOTICE.md](NOTICE.md).

## Disclaimer

CarPulse is an independent, non-commercial project for interoperability and personal use. "Škoda"/"Skoda",
"Volkswagen", "VW", model names and "Connect" product names are trademarks of their respective owners and are
used only to say which cars the app works with. The app only accesses data for an account you own and control.
You are responsible for complying with the carmakers' terms of service and your local laws. Provided **as is**,
with no warranty.

## License

MIT — see [LICENSE](LICENSE). OpenVW's copyright notice is kept there.
