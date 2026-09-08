# Vokie Phone Wi-Fi Plugin

This package moves the Android Phone Wi-Fi server out of the Vokie PC main
process and into a standalone Plugin Worker. It accepts the existing v2 Phone
TCP protocol, performs pairing/authentication, and bridges phone controls and
16 kHz mono PCM to the Vokie Plugin WebSocket protocol.

## Modes

`ptt_down.recordingMode` is mapped as follows:

| Phone value | Vokie Plugin session mode |
| --- | --- |
| `ptt` | `ptt` |
| `handsfree` | `handsfree-ptt` |
| `long` | `recording` |

BLE is intentionally not implemented. `send_enter` and `undo_last_output` are
forwarded through the standard Plugin command API.

## Host lifecycle contract

The Worker implements the standard Vokie Plugin WebSocket lifecycle and the
Host-side contract introduced with the unified Plugin configuration flow:

- After every registration the Host sends
  `{"type":"configuration_changed","requestId":"<uuid>","config":{...}}` and
  waits up to five seconds for the acknowledgement. This plugin exposes no
  user-configurable options, so any `config` object (including the empty
  object of a first install) is acknowledged with
  `{"type":"configured","requestId":"<same id>"}`. Legacy `configure`
  commands without a `requestId` are acknowledged without one.
- A repeated `start` is idempotent: it re-acknowledges `ready` without binding
  a second TCP listener or publishing a second mDNS advertisement.
- If the TCP listener cannot start, the Worker reports `state: "error"` and
  does **not** acknowledge `ready`, so the Host does not treat a dead server
  as live.
- The Worker only emits the Host-legal state values
  `starting/ready/connected/recording/stopped/error`. The pairing wait and the
  post-disconnect wait both use `ready` (server up, waiting for a phone);
  plugin-owned data never rides in the state core envelope:
  - `extensions.pairingInvite` — the `vokie://pair?...` QR invite,
  - `extensions.pairing` — `{ deviceName, pairingCode }` while a phone is
    confirming the pairing code,
  - `extensions.device` — `{ platform }` of the connected phone.
  The Host replaces the whole `extensions` object on every state event, so
  each emission carries the full current payload.
- When the Host ends a session on its own — `session_rejected` (for example
  `busy`), a terminal `session_state` (`success`/`error`) for the active
  request, or a plugin `stop`/`shutdown` while recording — the Worker sends
  the connected phone the same control message as the native phone Wi-Fi
  service:
  `{"v":2,"type":"recording_stopped","sessionId":<phone session>,"reason":"pc_stopped"}`.
  The Android app's `WifiPhoneTransport` stops its recording UI on this
  message, so the phone does not stay on a “recording” screen.

## Discovery (mDNS)

The Worker advertises `_vokie-phone._tcp` via `dns-sd -R`. The TXT record
matches the parsers in the Android app (`VokieDevice.java`) and the Harmony
client (`WifiDiscovery.ets`):

| Key | Value |
| --- | --- |
| `v` | `2` |
| `auth` | `sas-p256-v2` |
| `instance` | pairing-store instance id |
| `name` | PC display name |
| `ipv4` | comma-separated LAN IPv4 addresses (omitted when none) |

A service without `v=2`, `auth=sas-p256-v2`, a non-empty `instance`, or usable
`ipv4` addresses is ignored by the clients. `dns-sd` is a macOS tool; on
platforms without it the Worker keeps working through the QR invite but
reports an mDNS `error` state (known limitation). Override the binary with
`VOKIE_PHONE_DNS_SD`.

## Pairing

When the Worker starts it creates the same `vokie://pair?...` invite used by
the Android app and exposes it as `extensions.pairingInvite` in Plugin state.
The bundled UI renders that invite as a QR code. Open the Plugin page, scan
the QR code from Vokie Phone, and keep both devices on the same LAN. While
the phone confirms pairing, the UI also shows the six-digit verification code
from `extensions.pairing` so it can be compared with the phone's code.

The current standard Plugin API has no pairing-approval callback, so first pairing
is automatically approved after the user scans this Plugin's QR code. Set
`VOKIE_PHONE_REQUIRE_APPROVAL=1` to reject new devices when an external approval
extension is available. Trusted devices use the same v2 token proof as the native
Phone Wi-Fi service.

The pairing store is written to `VOKIE_PHONE_DATA_DIR`, or to `data/` beside the
Worker when that variable is absent. `VOKIE_PHONE_LISTEN_HOST` and
`VOKIE_PHONE_LISTEN_PORT` optionally pin the TCP listener (default
`0.0.0.0:0`, i.e. any interface with an ephemeral port). At most four
unauthenticated phone connections are kept pending; further connections are
dropped immediately, matching the native service.

## Package validation

Run the Vokie PC Plugin package validator against `plugins/vokie-phone` after
copying the package into a Vokie PC checkout. The Worker itself has no Electron
dependency; Vokie PC supplies its authenticated runtime connection when the
Plugin is enabled.

The bundled tests exercise the real protocol against a fake Host WebSocket and
a fake Android phone:

```bash
node --test plugins/vokie-phone/test/plugin.test.mjs
```
