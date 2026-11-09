# Car pictures kept out of the app

Background-removed pictures of Skoda and Volkswagen models that left India before 2020 (Laura, Fabia, Yeti, Jetta,
Passat, Ameo). They are not bundled in the app to keep it small. `index.json` lists each picture's model, year,
variant, colour and original source.

To ship one when an owner asks for it: move its `.webp` from here to `android/app/src/main/assets/cars/<brand>/<model>/`
and move its entry from this `index.json` to `android/app/src/main/assets/cars/index.json` (same fields). The app
picks it up from there; no code change is needed.

These are the carmakers' images (see NOTICE.md).
