# Credits

CarPulse is built on two MIT-licensed open-source projects. Their code is the foundation of this app,
and their copyright notices are kept in [LICENSE](LICENSE).

- **[OpenVW](https://github.com/biswatma/openvw)** by **biswatma** — the original app: reverse-engineered
  My Volkswagen Connect Plus (India) API client, live SignalR telemetry feed, instrument cluster, trips and
  driving stats, maps and widgets. CarPulse was forked from OpenVW v3.0
  (commit `10dca6a`, 20 Oct 2026) by way of OpenSkoda.
- **[OpenSkoda](https://github.com/darushah/openskoda)** by **Darshit Shah (darushah)** and contributors — the
  Skoda Connect port of OpenVW: Skoda brand support, Skoda models, ownership/plan details, and the
  v1.0.1 OTP fixes. CarPulse imports OpenSkoda's `fix/cerence-otp-login` work (Cerence OTP login, by
  Vibhor Sharma) and the Android Auto support added on top of it.

CarPulse merges both into one app that works with Volkswagen and Skoda cars in India.

The car pictures bundled with OpenVW and OpenSkoda (carmaker-style renders of unknown licence) are **not** included;
CarPulse draws its own car illustrations in code (`data/CarArt.kt`).

## Trademarks

"Volkswagen", "VW", "Škoda"/"Skoda", model names (Taigun, Virtus, Kushaq, Slavia, …) and "Connect" product
names are trademarks of their owners (Volkswagen AG / Škoda Auto a.s. and affiliates). CarPulse is an
independent project, not affiliated with, endorsed by, or sponsored by them. Brand and model names are used
only to describe which cars the app works with.
