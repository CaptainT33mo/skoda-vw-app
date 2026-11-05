# Play Console answers — CarPulse

Suggested answers for **Policy → App content** and related setup, based on what the code does. These are not
legal advice; read each question in the Console and adjust if anything differs.

## App access

All functionality is available **with special access** → add instructions:

> Tap **"No account? Try a demo Slavia"** on the sign-in screen. Demo mode shows a sample car with simulated live
> data, trips, updates, the car switcher (a Slavia and a Taigun) and Android Auto, without signing in. Real sign-in
> needs the mobile number of a Skoda/VW connected-car owner in India and an SMS one-time password, so we can't
> provide a test login.

## Ads

**No**, the app does not contain ads.

## Content rating (IARC questionnaire)

- Category: **Utility, Productivity, Communication or Other**
- Violence, sexuality, language, controlled substances, gambling: **No** to all
- Users can interact or exchange content with each other: **No**
- Shares the user's current location with other users: **No**
- Digital purchases: **No**

Expected result: rated for everyone (3+ / Everyone).

## Target audience and content

- Target age groups: **18 and over** (car owners)
- Appeals to children: **No**

## News app / COVID-19 / Government / Financial features / Health

**No** to each.

## Data safety

What the app actually does (see `PRIVACY.md`):

- The developer runs **no server** and receives no user data.
- The app sends the **mobile number** and one-time password to the carmaker's connected-car service (Cerence) to
  sign the user in, and receives the user's car data (identifiers, telemetry, trips, car location, owner
  name/email) from it.
- It sends the **car's location** to Android's Geocoder (place names) and map-tile requests to Esri, and hands
  searches/locations to Google Maps when the user taps.
- Everything stored stays in app-private storage, is not backed up, and is deleted on uninstall / clear storage.

Declarations (as submitted). The test for each box: does the app send it **off the phone**?

| Section | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS everywhere) |
| Account creation methods | **My app does not allow users to create an account** (users sign in to the carmaker account they already have) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: sign out removes sign-in tokens; uninstall or clearing app storage deletes everything the app stored. |

| Data type | Collected | Shared | Ephemeral | Required | Purposes |
|---|---|---|---|---|---|
| Location → **Approximate location** (the car's) | Yes | No | No | Required | App functionality |
| Location → **Precise location** (the car's parked spot) | Yes | No | No | Required | App functionality |
| Personal info → **Name**, **Email address** | Yes | No | No | Required | App functionality, Account management |
| Personal info → **User IDs** (account and car identifiers) | Yes | No | No | Required | App functionality, Account management |
| Personal info → **Phone number** | Yes | No | No | Required | App functionality, Account management |
| Device or other IDs (random install ID) | Yes | No | No | Required | App functionality |
| Everything else (address, financial, health, messages, photos, audio, files, calendar, contacts, app activity, web browsing, app info and performance) | No | — | — | — | — |

Notes:
- "Not shared": the map-tile and place-name lookups are service providers working for the app, which Play exempts
  from sharing; sign-in data goes to the user's own carmaker account at the user's request.
- The address is only typed in by the owner and never leaves the phone, so it isn't declared.
- Data isn't used for advertising, analytics or personalisation.

## Permissions

No declarations needed. The app uses no location, SMS, call log, background location, exact alarms, foreground
service or accessibility permissions. It asks at runtime for notifications (only when Car alerts are turned on),
and for the car-sensor permissions inside Android Auto (optional).

## Android Auto (Advanced settings → Form factors)

1. Add form factor **Android Auto**.
2. Release the bundle to a **testing track** first. In closed testing the car-quality review only warns.
3. The app's car category is **IoT** (`androidx.car.app.category.IOT`). Reviewers check it against the car app
   quality guidelines; demo mode lets them see the car screen without an account.

## Store settings

- App category: **Auto & Vehicles**
- Contact email: **playstore@vibhor.tech**
- Website: https://github.com/CaptainT33mo/skoda-vw-app
- Countries: **India** (the backend only serves Indian Skoda/VW accounts)
