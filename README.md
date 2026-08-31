# Vokie Phone for Android

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

The app checks `https://xiguasay.echooai.com/vokie/android/latest.json` first
and falls back to the GitHub Releases manifest when the primary manifest
cannot be fetched or parsed. These endpoints are configured as
`UPDATE_MANIFEST_URL` and `UPDATE_MANIFEST_BACKUP_URL` in `app/build.gradle.kts`.

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
