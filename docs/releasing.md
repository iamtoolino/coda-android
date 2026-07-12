# Releasing Coda

This document is for Coda maintainers. Contributors only need the debug-build instructions in the
project README.

## Signing identity

Android requires every update to use the same signing certificate. Losing the private key or its
password means losing the ability to publish updates that install over existing Coda releases.

The release key is stored outside the repository at `~/.android/coda-release.p12`. It must remain
encrypted, backed up, and restricted to its owner:

```sh
chmod 600 ~/.android/coda-release.p12
```

The keystore and its password must never be committed or uploaded as ordinary release assets.
Coda releases are currently signed locally.

Official release APKs use this signing-certificate SHA-256 fingerprint:

```text
4C:B1:EB:7A:B5:1D:A8:6F:65:82:40:E8:DB:65:44:7B:
D9:D7:87:61:38:2F:10:76:07:EF:7B:58:4E:88:89:F7
```

## Release checklist

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run the debug verification suite:

   ```sh
   ./gradlew testDebugUnitTest lintDebug assembleDebug
   ```

3. Build the signed, minified release. The helper reads the keystore password without terminal echo
   and does not save it in shell history or a project file:

   ```sh
   ./scripts/release.sh
   ```

4. Verify the APK and compare its certificate fingerprint with the value above:

   ```sh
   apksigner verify --verbose --print-certs \
     app/build/outputs/apk/release/app-release.apk
   ```

5. Record the release file's SHA-256 digest:

   ```sh
   shasum -a 256 app/build/outputs/apk/release/app-release.apk
   ```

6. Install the APK on a clean emulator or test device and confirm that the first-run login screen
   opens without a crash.
7. Create an annotated `vX.Y.Z` tag, publish the corresponding GitHub release, and attach
   `app-release.apk`. Include the APK SHA-256 digest and signing-certificate fingerprint in the
   release notes.

The build reads signing configuration from these environment variables when a non-interactive build
is needed:

```text
CODA_SIGNING_STORE_FILE
CODA_SIGNING_STORE_PASSWORD
CODA_SIGNING_KEY_ALIAS
CODA_SIGNING_KEY_PASSWORD
```
