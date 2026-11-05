# Publishing CarPulse on Google Play

Status and to-do list for the first Play release. Items marked **Blocker** stop an upload or are likely to get
the app rejected or removed.

## 1. Risks to decide on first

- **Blocker (policy) — unofficial API.** Play's Device and Network Abuse policy prohibits "apps that access or
  use a service or API in a manner that violates its terms of service". CarPulse uses the carmakers' private,
  undocumented API with keys taken from the official apps. If Skoda, Volkswagen or Cerence object, Google can
  remove the app or suspend the developer account. No code change fixes this. Options: accept the risk; ask
  SAVWIPL/Cerence for permission; or distribute via GitHub Releases / F-Droid only.
- [x] **Car artwork — replaced.** The bundled carmaker-style photos are gone; cars are CarPulse's own
  illustrations drawn in code (`data/CarArt.kt`). No carmaker logos anywhere; the launcher icon and sign-in badge
  are CarPulse's own mark.
- [x] **Reviewer access — demo mode built.** Reviewers can't receive an owner's OTP, so the sign-in screen has
  "No account? Try a demo Slavia/Taigun": a labelled sample car (two, to show the switcher), no network. In
  Play Console → App access, say: *"Tap 'No account? Try a demo car' on the sign-in screen. Everything except
  live data from a real car works in demo mode, including Android Auto."*
- **Trademarks in the listing.** The name "CarPulse" and the icon are neutral. In the listing, mention brands
  only to describe compatibility ("Works with Skoda and Volkswagen connected cars in India") together with the
  "not affiliated" disclaimer. No carmaker logos in screenshots or the feature graphic.

## 2. Code and build

- [x] `applicationId` is `tech.vibhor.carpulse` (debug builds: `tech.vibhor.carpulse.debug`). It can never
      change after the first upload.
- [ ] **Blocker:** create an upload key and `android/keystore.properties`, then build with
      `./gradlew :app:bundleRelease` (an `.aab`). Enrol in Play App Signing.
- [x] Release build works (the JPEG-named-`.png` image that broke it is fixed).
- [x] Target SDK 36.
- [x] No cloud backup of sign-in tokens (`allowBackup="false"`).
- [x] Permissions: internet and network state, notifications (only when Car alerts are turned on), and the
      optional Android Auto car-sensor permissions. No phone location. Biometric unlock goes through Android's
      own prompt; CarPulse never sees fingerprints or faces.
- [ ] Bump `versionCode` for every upload.

## 3. Play Console

- [ ] **Developer account.** A personal account created after 13 Nov 2023 must run a **closed test with at least
      12 testers, opted in for 14 continuous days**, before it can apply for production. An organisation account
      (needs a D-U-N-S number) skips this.
- [ ] **Privacy policy URL:** publish [PRIVACY.md](PRIVACY.md) at a public URL (e.g. the public GitHub file or
      GitHub Pages) after filling in its date and contact email.
- [ ] **Data safety form.** The developer collects nothing. The app sends the phone number/OTP to Cerence for
      sign-in and receives car data and location from it; coordinates go to the device Geocoder for place
      names. Encrypted in transit: yes. Deletion: uninstall / clear storage; sign-out removes tokens. Get a
      second opinion on how Play classifies data the app passes directly to the carmaker's service.
- [ ] **App access:** paste the demo-mode instructions above.
- [ ] Content rating questionnaire, target audience 18+, no ads.
- [ ] Store listing: 512×512 icon, 1024×500 feature graphic, phone screenshots (use privacy mode so no real
      VIN / reg / name shows).
- [ ] **Android Auto:** opt in under the Android Auto form factor. The app is in the *IoT* category and goes
      through Google's Android Auto review against the car app quality guidelines. Test it on the Desktop Head
      Unit first.

## 4. Before going public

- [ ] Confirm Volkswagen sign-in with a real VW account (still untested), or label VW support as beta in the
      listing.
- [ ] Publish the GitHub repo with README, LICENSE (keeps the OpenVW copyright line) and NOTICE.md.
