# Publishing CarPulse on Google Play

Step-by-step. Prepared material: [store/listing.md](store/listing.md) (listing text),
[store/play-console.md](store/play-console.md) (form answers), [store/graphics](store/graphics) and
[store/screenshots](store/screenshots).

## 0. Decide on the policy risk (once)

Play's Device and Network Abuse policy prohibits "apps that access or use a service or API in a manner that
violates its terms of service". CarPulse uses the carmakers' private, undocumented API with keys taken from their
apps. If Skoda, Volkswagen or Cerence object, Google can remove the app or suspend the developer account. No code
change fixes this. Alternatives: ask for permission, or distribute through GitHub Releases / F-Droid only.

## 1. Developer account

- [x] Google Play developer account: **individual (personal)**.
- [ ] If the account was created after 13 Nov 2023 (Play Console shows it under **Dashboard → Apply for production
      access**): run a **closed test with at least 12 testers opted in for 14 continuous days** before production.

## 2. Upload key and the release bundle

- [ ] Create the upload key (keep the file and passwords safe; Play App Signing holds the real signing key):
      ```bash
      cd android
      keytool -genkeypair -v -keystore carpulse-upload.jks -alias carpulse-upload \
        -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=CarPulse"
      cp keystore.properties.example keystore.properties   # then put your passwords in it
      ```
- [ ] Build: `./gradlew :app:bundleRelease` → `android/app/build/outputs/bundle/release/app-release.aab`.
      Check it's signed with your key, not "Android Debug":
      `jarsigner -verify -verbose:summary app/build/outputs/bundle/release/app-release.aab | grep "Signed by"`
- [ ] Bump `versionCode` in `android/app/build.gradle.kts` for every upload after the first.

Done in code: package `tech.vibhor.carpulse`, version 1.0.0 (code 1), target SDK 36, release build works, tokens
excluded from backup, no carmaker logos or photos.

## 3. Public pages

- [x] `PRIVACY.md` has the contact email (playstore@vibhor.tech) and date.
- [ ] Push to GitHub. Privacy policy URL:
      https://github.com/CaptainT33mo/skoda-vw-app/blob/main/PRIVACY.md

## 4. Create the app in Play Console

- [ ] Create app: name **CarPulse: Connected Car Live**, App, Free, default language English (India).
- [ ] **Store listing**: text from [store/listing.md](store/listing.md); icon, feature graphic and the 8
      screenshots from `store/`.
- [ ] **App content**: answers in [store/play-console.md](store/play-console.md) (app access via demo mode, ads,
      content rating, target audience, data safety, government/financial/health: no).
- [ ] **Store settings**: category Auto & Vehicles, contact email.
- [ ] **Countries**: India.

## 5. Android Auto

- [ ] Advanced settings → Form factors → **Add form factor → Android Auto**.
- [ ] The car app is reviewed against the car app quality guidelines (IoT category). It only blocks open testing
      and production, so the closed test gives early feedback first.
- [ ] Test on the Desktop Head Unit (or a car) before going to production; it hasn't been run on one yet.

## 6. Testing, then production

- [ ] Upload the bundle to **Internal testing** and install it from Play on your own phone.
- [ ] **Closed testing** with 12+ testers for 14 days (personal accounts).
- [ ] Apply for production access, then release to **Production**.

## Before going public, also

- [ ] Confirm Volkswagen sign-in with a real VW account (still untested), or call VW support "beta" in the listing.
