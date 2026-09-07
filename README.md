# Coda — Album-First Navidrome Client for Android

Coda is a focused Android client for Navidrome and compatible OpenSubsonic servers. It keeps
artwork, albums, and the listening queue at the center of the experience.

Coda is an independent community project and is not affiliated with or endorsed by Navidrome.

<p align="center">
  <img src="docs/screenshots/01_home.webp" width="23%" alt="Coda home screen">
  <img src="docs/screenshots/02_artist_discography.webp" width="23%" alt="Artist discography">
  <img src="docs/screenshots/03_album_view_1.webp" width="23%" alt="Album and track view">
  <img src="docs/screenshots/06_now_playing_2.webp" width="23%" alt="Now playing screen">
</p>

## Highlights

- Artwork-led dark design with album-derived colors and an immersive Now Playing view.
- Album-first browsing with chronological discographies, multidisc releases, playlists, ratings,
  and search, fetched directly from your server.
- A visible playback queue with saved position, cross-client queue handoff, and Continue Listening
  album progress shared with [Coda for macOS](https://github.com/iamtoolino/coda-macos).
- Original-format streaming on Wi-Fi or Ethernet and server-configured Opus transcoding on cellular.
  A rolling four-track audio cache helps with interrupted connectivity; there are no offline downloads.
- Background playback, scrobbling, notification and Bluetooth controls.
- Android Auto browsing, search, playback, and artwork through the phone's connection, including
  private servers reached over a VPN.

## Requirements

- Android 9 or newer (API 28+).
- A Navidrome server or compatible OpenSubsonic implementation.
- Network access to that server; HTTPS is recommended outside a trusted network.

Coda supports one server and account at a time.

## Install

Download the APK from the [latest GitHub release](https://github.com/iamtoolino/coda-android/releases/latest)
and open it on your phone. Allow installation from your browser or file manager when Android asks,
then connect to your server inside Coda.

## Build from source

Install JDK 17 and Android SDK 36 with its build tools. Set `JAVA_HOME` to your JDK and configure the
SDK location through `ANDROID_HOME` or `sdk.dir` in `local.properties`.

Clone the repository and build a debug APK:

```sh
git clone https://github.com/iamtoolino/coda-android.git
cd coda-android
./scripts/gradle.sh assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the automated checks with:

```sh
./scripts/verify.sh
```

For the Compose UI smoke test, use a booted test emulator's explicit serial:

```sh
./scripts/ui-test.sh emulator-5554
```

See [release instructions](docs/releasing.md) for signed builds and the
[development baseline](docs/development-baseline.md) for additional verification guidance.

## Privacy

Coda contains no analytics, advertising, or tracking SDK. Server credentials are entered in the app
and encrypted with a non-exportable Android Keystore key. Android password-manager Autofill is supported.

Artwork and a bounded audio window are cached on the device. Disconnecting removes the saved
credentials and clears that account's caches. Coda does not maintain a separate local library database.

## License

Copyright 2026 iamtoolino.

Coda's source code is licensed under the [Apache License, Version 2.0](LICENSE).
Bundled libraries retain their respective licenses.
