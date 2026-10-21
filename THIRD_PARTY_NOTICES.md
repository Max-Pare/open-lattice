# Third-party notices

open-lattice is licensed under the GNU Affero General Public License v3.0 (see `LICENSE`).
It contains code adapted from, or protocol knowledge taken from, the projects below.

## elegoo-cc2-webui — MIT
Copyright (c) nyakaspeter. https://github.com/nyakaspeter/elegoo-cc2-webui

The CC2 status / sub-status mapping in `protocol-cc2/.../Cc2StatusMapper.kt` and several
request parameter shapes in `Cc2Driver.kt` are ported from `server/src/cc2/mapper.ts` and
`server/src/cc2/cc2Printer.ts`.

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software
> and associated documentation files (the "Software"), to deal in the Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions: The above copyright notice and this
> permission notice shall be included in all copies or substantial portions of the Software.
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.

## Protocol references (no code copied)
- ELEGOO-3D/elegoo-link (Apache-2.0) — the SDK ElegooSlicer uses for printer communication.
- bjan/pycentauri (Apache-2.0) — CC1/CC2 protocol notes.
- danielcherubini/elegoo-homeassistant (MIT) — `docs/CC2_PROTOCOL.md`.
- cbd-tech/SDCP-Smart-Device-Control-Protocol-V3.0.0 — SDCP v3 specification.

## Libraries
- HiveMQ MQTT Client — Apache-2.0
- OkHttp — Apache-2.0
- kotlinx.coroutines, kotlinx.serialization — Apache-2.0
- AndroidX / Jetpack Compose — Apache-2.0

"ELEGOO", "Centauri Carbon" and "Elegoo Matrix" are trademarks of their owners. open-lattice is
not affiliated with or endorsed by ELEGOO.
