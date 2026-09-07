# Album continuation on Android

## Canonical specification

The **Coda macOS repository owns the shared specification** for both clients:

- [Album Resume Bookmark protocol](https://github.com/iamtoolino/coda-macos/blob/main/docs/album-resume-bookmarks.md):
  record format, optional writer diagnostics, byte limits, eligibility, ordering, and retention.
- [Coda album resume design](https://github.com/iamtoolino/coda-macos/blob/main/docs/coda-album-resume-design.md):
  shared product semantics, completion triggers, and refresh behavior.

These links deliberately follow `main`, so protocol changes have one source of truth. Propose
shared changes there and verify both clients against them; do not maintain a second protocol
specification here. This remains an experimental application convention, not an official
OpenSubsonic extension. Saved-queue handoff is separate.

This document covers only Android integration, presentation, and verification. Service ownership,
account-scoped work, and coordinator data flow are described in [architecture.md](architecture.md).
Android's reader ignores writer diagnostics and its encoder includes them when the canonical byte
limit permits; compatibility tests below protect that behavior.

## Phone presentation

Home places Continue Listening between Recently Played and Playlists. Plain cards show artwork,
album title, and album artist, without rating badges, resume-track text, runtime, or a chevron.
Include the local current album while playing or paused/restored. Tapping only opens normal album
detail; it never fetches songs to start playback or changes the queue.

Album detail resolves the saved target in its already-loaded canonical tracklist. Immediately above
that track, a small bookmark and RESUME label accompany icon-only Play and Append actions using the
album header's icons with accessible names and 48 dp touch targets. A continuous 10% theme-accent
background with rounded outer corners encloses the saved track and every subsequent track,
including intervening disc headings. The hero and its whole-album actions remain unchanged.

Resume Play replaces the queue with that canonical suffix and starts at its beginning. Resume
Append uses the existing append path, preserving playback and remaining paused with an empty queue.
The shared append path explicitly clears retained play intent when empty, including after clearing
a playing queue.
Both use canonical album artwork, stay in album detail, and never edit the bookmark. Individual
track taps retain ordinary whole-album playback at the tapped index. Android has no internal
track-to-queue drag/drop or track-selection mechanism, so no Resume drag gesture is added.

Hide the entire detail treatment when this album is the current playback entry, including while
paused/restored; songs elsewhere in the queue do not suppress it. Observe playback and bookmark
changes without reloading or navigating. Missing targets or failed album loads omit the treatment,
without fallback to track one or deleting the bookmark. Bookmark refresh never navigates.

## Verification

`./scripts/verify.sh` covers protocol, eligibility, ordering, retention, serial writes, refresh races,
failure retention, cancellation, detail target resolution, and completion independence from scrobbling.
`./scripts/ui-test.sh <explicit-emulator-serial>` includes credential-free shelf and detail tests for
suffix actions, multidisc targets, live current-album suppression, missing targets, compact layout,
large text, and accessible touch targets. Local silent Media3 fixtures verify
empty-queue pause and populated-queue playback/index/position preservation on append.
Live cross-client testing remains separate: finish tracks on each platform, foreground the other,
resume an album, and verify isolated/final completions and screen-off playback against a real server.
