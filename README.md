# Vokie Phone for Android

[简体中文](README.zh-CN.md)

Vokie Phone is the Android companion app for connecting a phone to the Vokie
desktop app over the local network. The project is an independent Gradle
Android application. Visit [vokie.com](https://vokie.com) for more information.

## Build

Open this repository in Android Studio, or run the Gradle tasks from the
repository root with a JDK supported by Android Gradle Plugin 8.7.3:

```bash
./gradlew :app:test
./gradlew :app:assembleDebug
```

The debug APK is written to
`app/build/outputs/apk/debug/app-debug.apk`. The current release is version
`0.3.11` (`versionCode 14`).

For release signing, copy `keystore.properties.example` to
`keystore.properties` and provide the local keystore credentials. The
credentials file and keystore directory are ignored by Git.

## GitHub Releases

Pushing a tag such as `v0.3.5` runs `.github/workflows/android.yml`. Pull
requests run the unit tests; version tags run tests, build a signed release,
generate `latest.json`, and publish both files to a GitHub Release. Configure
these repository secrets before publishing:

`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEY_PASSWORD`.

## Relationship to Vokie PC

The app speaks the phone pairing and recording protocol implemented by Vokie
PC. Protocol changes must be coordinated across both repositories and
documented in the PC repository's phone integration specifications.

## Project Changes

The Android phone Wi-Fi service is now also packaged as a standalone Vokie
Plugin at [`plugins/vokie-phone`](plugins/vokie-phone). The Plugin Worker owns
the phone TCP server, QR pairing invite, authentication, recording-mode
mapping, and PCM forwarding. The Android app protocol remains unchanged. This
Plugin currently supports Wi-Fi only; BLE is outside its scope.

This is an officially supported Vokie integration: Vokie provides the Plugin
runtime, lifecycle, session protocol, audio pipeline, and UI bridge required by
this project. The phone-specific implementation remains in this repository so
it can evolve independently from the Vokie PC application.

The current Plugin package version is `0.1.1`. Its UI uses the Android app's
launcher artwork and displays the live pairing QR code supplied through the
Plugin state `extensions.pairingInvite` field.

## Generating the Plugin

Use the `vokie-plugin-creator` skill (Vokie Plugin Create) to generate or
update the package. In Codex, describe the device and desired output directory,
for example:

```text
Use the vokie-plugin-creator skill to create a Vokie Plugin for <device>
with Wi-Fi transport. Put the generated package in plugins/<plugin-id>.
```

The skill reads the Plugin protocol contract, creates the manifest, Worker,
custom UI, assets, and focused tests, then validates the package. Do not treat
the existing phone Plugin directory as a template for unrelated devices.

The generated directory must be self-contained and include:

```text
plugins/vokie-phone/
├── vokie.plugin.json       # immutable Plugin id, version, capabilities
├── worker/index.mjs        # authenticated Host WebSocket Worker
├── ui/index.html           # custom status and pairing page
└── assets/                 # SDK, QR encoder, and App icon
```

The manifest declares the `wifi` transport, supported recording capabilities,
UI entrypoint, Worker entrypoint, and package-relative icon. The Worker uses
the authenticated runtime connection supplied automatically by Vokie PC;
phone protocol details stay inside the Plugin. A Plugin is not limited to the
capabilities listed here: it can implement additional device behaviors,
commands, transports, and opaque `extensions` as long as it preserves the
standard Vokie Plugin lifecycle and WebSocket contract. Validate changes with:

```bash
node --test plugins/vokie-phone/test/plugin.test.mjs
node --check plugins/vokie-phone/worker/index.mjs
```

More protocol details and limitations are documented in
[`plugins/vokie-phone/README.md`](plugins/vokie-phone/README.md) and
[`Testing/android-phone-wifi-plugin.md`](Testing/android-phone-wifi-plugin.md).

## Loading the Plugin into Vokie

1. Prepare the complete `plugins/vokie-phone` directory. Keep the manifest,
   Worker, UI, and referenced assets together; the package is self-contained.
2. In Vokie PC's Plugin settings, choose **Install Plugin** and select this
   directory (or copy the directory into the configured local Plugin folder).
3. Vokie validates `vokie.plugin.json`, resolves the UI/Worker/icon paths, and
   adds the manifest to the available Plugin list.
4. Enable or reload **Vokie Phone Wi-Fi** from the Plugin list. Vokie PC then
   launches `worker/index.mjs` as a supervised Plugin Worker and supplies its
   authenticated runtime connection automatically.
5. Open the Plugin detail page to use its bundled UI. Reinstall or reload the
   package after changing any Plugin files so the installed copy is refreshed.

After the Plugin is loaded, its Worker runtime follows the standard Vokie
Plugin lifecycle and the UI can display the phone pairing QR code.
