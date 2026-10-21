# open-lattice

A LAN-only Android app for ELEGOO 3D printers: a replacement for the cloud-bound *Elegoo Matrix* app.
Everything talks directly to the printer on your network, or over a VPN with subnet routing
(Tailscale, WireGuard, ...). No ELEGOO account or cloud is involved.

**Status:** early development. Centauri Carbon 2 first.

## Supported printers
| Printer | Protocol | Status |
|---|---|---|
| Centauri Carbon 2 / Centauri 2 | MQTT on the printer (`:1883`), HTTP upload (`:80`), MJPEG camera (`:8080`) | in progress |
| Centauri Carbon | SDCP v3 WebSocket (`:3030`) | planned |
| Saturn 4 Ultra / Mars 5 Ultra | SDCP v3 WebSocket (`:3030`) | planned |

## Printer setup (Centauri Carbon 2)
1. On the printer: **Settings → Network → LAN Only Mode** on. The local API is closed otherwise.
   (Elegoo Matrix stops working in this mode; that is the point of this app.)
2. Note the **access code** shown on the printer (default `123456` if none is set).
3. In open-lattice, add the printer by discovery or by IP / hostname, and enter the access code.

Over a VPN, broadcast discovery does not cross subnets: add the printer by IP. A direct
probe to that IP still works and fills in the serial number automatically.

## Building
Requires JDK 17 and the Android SDK (platform 36).

```sh
./gradlew :core:test :protocol-cc2:test   # JVM tests (protocol replayed from real captures)
./gradlew :app:assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
```

Live, read-only protocol checks against a real printer (sends nothing that changes state):

```sh
OPEN_LATTICE_CC2_HOST=192.168.1.x OPEN_LATTICE_CC2_CODE=<access code> ./gradlew :protocol-cc2:test --tests '*Cc2LiveTest*'
```

## Layout
- `core/`: protocol-independent printer model, driver interface, and MJPEG reader (pure Kotlin/JVM)
- `protocol-cc2/`: Centauri Carbon 2 driver: MQTT session, status mapping, uploads, discovery (pure Kotlin/JVM)
- `app/`: Android app (Jetpack Compose)

## License
AGPL-3.0-only. See `LICENSE` and `THIRD_PARTY_NOTICES.md`.
