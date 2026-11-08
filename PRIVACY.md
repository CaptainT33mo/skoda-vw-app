# CarPulse privacy policy

_Last updated: 6 November 2026_ · Contact: playstore@vibhor.tech

CarPulse is an open-source app that shows data from your own connected Skoda or Volkswagen car. It has no
server of its own: we, the developers, never receive your data. This policy explains what the app handles on
your phone and which services it talks to on your behalf. The source code is public, so every statement here
can be checked.

## What the app handles

| Data | Why | Where it goes |
|---|---|---|
| Your mobile number and the SMS one-time password | To sign you in to your car's connected-car account | The carmaker's savwconnect.com server (the sign-in the MySkoda Plus app uses), or Cerence (the carmakers' telematics provider) when savwconnect doesn't know the number |
| Sign-in tokens | To stay signed in | Stored on your phone only; not included in cloud backups |
| Car identifiers (VIN, car ID, registration number), model, variant, colour | To find and show your car | Received from Cerence / the carmaker's server; stored on your phone |
| Owner name and email from your car account | Shown on the Owner screen | Received from Cerence / the carmaker's server; stored on your phone |
| Car telemetry: fuel, speed, engine data, odometer, alerts | The app's main purpose | Received from Cerence; kept in memory and a small on-phone cache for widgets |
| Car location, trips and their start/end places | Trips, parked location, map | Received from Cerence; place names looked up as below |
| A random device ID the app creates (not tied to your phone's hardware) | Device registration with the carmaker's server | The carmaker's savwconnect.com server |
| Speed, fuel level and odometer from your car's head unit (only if you allow it in Android Auto) | Faster live speed on the car screen | Used on your phone only |

If you turn on **Car alerts**, the app repeats the same requests about every 15 minutes in the background and
shows notifications on your phone; nothing extra leaves your phone. **App lock** uses Android's own fingerprint /
face / screen-lock prompt; the app only learns "unlocked" or not.

CarPulse does **not** use your phone's own location, contacts, camera or microphone, and contains **no ads,
analytics, crash reporting or tracking SDKs**.

## Services the app talks to

- **Cerence** (`*.cerenceapi.com`) and the carmaker's **savwconnect.com** servers: the same services the official
  Skoda and Volkswagen apps use. Their own privacy policies apply to the data they hold about your account.
- **Android's Geocoder** (usually provided by Google): turns a car location into a place name. It receives the
  coordinates being looked up.
- **Esri** map tiles: the map requests tiles for the area on screen, which reveals that area to Esri.
- **Google Maps**: only when you search on the Locate tab or tap "Open in Google Maps" / "Directions". The app
  hands the search text and your car's parked location (as the place to search around) to the Google Maps app or
  website. Saved workshops and phone numbers stay on your phone; "Call" only opens your dialer.
- **Android Auto**: when you use the car screen, Android Auto (Google) displays what the app sends it.

## Keeping and deleting data

Everything the app stores stays on your phone. **Sign out** removes your sign-in tokens. **Uninstalling the app,
or clearing its storage** in Android settings, deletes everything it stored. Data held by Cerence or the carmaker
about your car account is controlled by them; contact them, or use the official app, to manage it.

## Children

CarPulse is meant for car owners and is not directed at children.

## Changes

Changes to this policy are published in the app's source repository, with the date above updated.

CarPulse is independent and not affiliated with Škoda Auto, Volkswagen AG, Škoda Auto Volkswagen India or Cerence.
