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

## Features (Centauri Carbon 2)
Verified against a real CC2 on firmware 02.01.01.07 unless marked *untested*.

- Add printers by LAN discovery or by IP / hostname (works over VPN), access-code check
- Live status: job progress, layers, time left, temperatures, fans, light, speed mode
- Live camera (MJPEG), full screen, one shared stream per printer
- Pause / resume / stop; light toggle
- Set temperatures, fans, speed mode; jog and home axes (*untested*)
- Files on the printer and USB: thumbnails, details, storage usage, delete (*untested*)
- Upload a sliced file from the phone or via Android share (*untested*)
- Start a print with plate type, auto bed leveling, timelapse and CANVAS slot mapping (*untested*)
- Print history from the printer
- Filament: external spool and CANVAS trays, edit material/colour (*untested*), load/unload, auto refill (*untested*)
- Background monitor while printing: progress notification and alerts for finished, stopped,
  errors and filament runout; plus a 15-minute check for prints started while the app was closed

Not possible without ELEGOO's cloud: account binding, the Nexprint model library and its cloud
slicing, firmware updates, and history synced across devices.

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
