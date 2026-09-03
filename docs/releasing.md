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

## Candidate checklist

Every item below is a release gate. Keep generated reports and dependency listings in `/tmp` or
another ignored directory; do not commit local release audits.

1. Confirm the candidate is the intended clean commit and update `versionCode` and `versionName` in
   `app/build.gradle.kts`.
2. Run the complete debug verification suite:

   ```sh
   ./scripts/verify.sh
   ```

3. Build the signed, minified candidate. The helper runs release unit tests, `lintRelease`, merged
   manifest generation, and release assembly. It reads the keystore password without terminal echo
   and does not save it in shell history or a project file:

   ```sh
   ./scripts/release.sh
   ```

4. Inspect the release merged manifest under `app/build/intermediates/merged_manifests/`. Confirm the
   application is not debuggable, no test runner or debug-only component is packaged, and every
   exported component and permission is intentional. In particular, retain the media service,
   media-button receiver, and bounded artwork provider required by Android Auto and system playback.
5. Inspect the packaged closure, not only Gradle declarations:

   ```sh
   ./scripts/gradle.sh :app:dependencies --configuration releaseRuntimeClasspath
   apkanalyzer manifest print app/build/outputs/apk/release/app-release.apk
   apkanalyzer files list app/build/outputs/apk/release/app-release.apk
   ```

   Confirm instrumentation, test manifests, debug helpers, credentials, private URLs, and live-test
   entry points are absent. Audit the resulting runtime dependencies for license and notice
   obligations before approving the candidate.
6. Verify the APK and compare its certificate fingerprint with the value above:

   ```sh
   apksigner verify --verbose --print-certs \
     app/build/outputs/apk/release/app-release.apk
   ```

7. Record the release file's SHA-256 digest:

   ```sh
   shasum -a 256 app/build/outputs/apk/release/app-release.apk
   ```

8. Clean-install the candidate on an emulator or test device and confirm first-run login.
9. Install the candidate over the previous signed release without clearing data. Confirm encrypted
   credentials remain usable, a paused queue and position restore, artwork and transient audio caches
   remain valid, and playback can resume from system controls.
10. Run the deterministic UI smoke test and the manual platform checks appropriate to the change:

    - TalkBack, font scaling, and keyboard/D-pad behavior on Now Playing.
    - Background playback, notification and Bluetooth controls, audio focus, and noisy-output handling.
    - Debuggable-process death without Force Stop, followed by metadata, queue, position, and system
      Play restoration.
    - Android Auto DHU cold start, browse, search, artwork, complete album/playlist playback, queue,
      split-screen controls, and recovery without opening the phone activity.
    - A physical phone/car path for material media-session or Android Auto changes.
    - Album continuation: natural completion while screen-off, foreground refresh after macOS writes,
      resume at the canonical track boundary, and final-track marker removal. Best-effort pending
      bookmark work need not survive process death; local playback restoration must still work.

11. Only after the candidate is approved, create an annotated `vX.Y.Z` tag, publish the corresponding
    GitHub release, and attach `app-release.apk`. Include the APK SHA-256 digest and signing-certificate
    fingerprint in the release notes.

The build reads signing configuration from these environment variables when a non-interactive build
is needed:

```text
CODA_SIGNING_STORE_FILE
CODA_SIGNING_STORE_PASSWORD
CODA_SIGNING_KEY_ALIAS
CODA_SIGNING_KEY_PASSWORD
```
