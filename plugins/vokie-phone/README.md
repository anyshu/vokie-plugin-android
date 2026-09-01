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

## Pairing

When the Worker starts it creates the same `vokie://pair?...` invite used by the
Android app and exposes it as `extensions.pairingInvite` in Plugin state. The bundled UI
renders that invite as a QR code. Open the Plugin page, scan the QR code from
Vokie Phone, and keep both devices on the same LAN.

The current standard Plugin API has no pairing-approval callback, so first pairing
is automatically approved after the user scans this Plugin's QR code. Set
`VOKIE_PHONE_REQUIRE_APPROVAL=1` to reject new devices when an external approval
extension is available. Trusted devices use the same v2 token proof as the native
Phone Wi-Fi service.

The pairing store is written to `VOKIE_PHONE_DATA_DIR`, or to `data/` beside the
Worker when that variable is absent.

## Package validation

Run the Vokie PC Plugin package validator against `plugins/vokie-phone` after
copying the package into a Vokie PC checkout. The Worker itself has no Electron
dependency and starts with `VOKIE_PLUGIN_WS_URL` and `VOKIE_PLUGIN_TOKEN`.
