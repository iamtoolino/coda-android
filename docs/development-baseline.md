# Development baseline

This is the compact, reproducible starting point for Android development. The product contract in
`product.md`, the ownership model in `architecture.md`, and the repository `AGENTS.md` remain the
authoritative specifications.

## Current state

- **Complete and in active use:** authenticated Navidrome/OpenSubsonic library browsing, search,
  album and playlist queues, Media3 playback, artwork-led phone UI, ratings, scrobbling, shared queue
  handoff, process-death restoration, the rolling audio cache, and Android Auto browse/playback.
- **Deterministically covered:** URL/API model and bounded-pagination behavior, network policy,
  controller authorization, queue ownership and service-side saving, restoration and account-cleanup
  policy, remote-resource lifecycle, scrobbling, artwork accent extraction, audio-cache window
  selection, and credential-free Compose login and seek-control tests.
- **Manual/platform integration checks:** real server playback, audio focus, process death and media
  buttons, Android Auto Desktop Head Unit, physical phone/car behavior, and visual quality. These
  require explicit scenarios and are not folded into the deterministic suite.
- **Confirmed broken at this baseline:** none. Missing automation and untested device/server
  combinations are verification gaps, not claims that those paths are correct.
- **Historical inputs:** `../android-accent-color-handoff.md` and `../queue-handoff-android.md`
  explain prior decisions. They are not current specifications where they disagree with product,
  architecture, `AGENTS.md`, or code.
- **Platform-specific:** Media3 service/session ownership, foreground-service behavior, Android Auto,
  Android Keystore, connectivity policy, system bars, and Android lifecycle behavior must remain
  native Android implementations rather than direct macOS ports.

## Deterministic verification

Run the host-side suite and produce a provenance line plus SHA-256 for the debug APK:

```sh
./scripts/verify.sh
```

This runs unit tests, Android lint, and both the debug app and instrumentation-test builds. The app
artifact is `app/build/outputs/apk/debug/app-debug.apk`. A known-good artifact report is meaningful
only when it names the exact commit and reports a clean tree.

With a booted emulator, run the credential-free Compose UI tests using its explicit serial:

```sh
./scripts/ui-test.sh emulator-5554
```

The UI tests render `LoginScreen` and the Now Playing seek control in isolation, perform no server
request, and do not depend on credentials or existing app data. Live Navidrome, audio, lifecycle,
TalkBack, and car checks remain separate.

To run the explicit live artwork diagnostic against an already connected emulator, use:

```sh
./scripts/check-artwork-loading.sh emulator-5554
```

The script refuses physical-device serials, bypasses Coil memory and disk reads, and loads 100
canonical 1200 px album covers through Coda's production image loader. Its library-derived TSV
report is written to `/tmp` by default and must not be committed.

## Repository hygiene baseline

Build outputs, IDE state, local SDK configuration, signing material, databases, logs, reports,
profiles, and captures are ignored. Runtime credentials belong only in Android's encrypted
credential store; signing values are supplied through the documented environment variables.

Before committing, inspect both filenames and content:

```sh
git status --short
git diff --check
git diff --cached --name-only
git diff --cached
```
