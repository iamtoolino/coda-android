# Album continuation v1

Android follows Coda macOS's album-resume reference semantics. This is an experimental application
convention, not an official OpenSubsonic extension. Saved-queue handoff is separate and unchanged.

## Server record

Use `getBookmarks`, `createBookmark`, and `deleteBookmark`. A bookmark is per authenticated user
and song; create overwrites the existing row. Anchor each unfinished album on its first canonical
song, position zero (omitted zero on read is acceptable), with compact UTF-8 JSON:

```json
{"protocol":"album-resume-bookmark","protocolVersion":1,"resumeSongId":"next-song-id","writer":{"client":"CodaAndroid","platform":"Android","appVersion":"0.1.0"}}
```

The three non-writer fields are required, with exact protocol/version and a nonempty song ID.
Ignore unknown fields and accept arbitrary key order. Reject malformed/unsupported markers.
Writer is optional diagnostic metadata, never ownership or ordering. Its `client`, `platform`, and
`appVersion` strings are each optional. Coda writers continue supplying all three when space permits.
Progress readers on both platforms ignore the entire `writer` field, including missing, partial,
or incorrectly typed values; it must not invalidate otherwise valid progress. Diagnostic tools may
inspect it independently. Omit it when necessary to
fit the 255-byte UTF-8 ceiling; never upload an oversized record. There is no installation ID.
Album identity and presentation come from the bookmark's media entry; timestamps come from the
server. Do not log private bookmark payloads or authenticated URLs.

## Completion and order

Eligible media has a nonempty album ID, optional `type=music`, optional `mediaType=song`, and
verified membership in the canonical album. Explicit other types are rejected; absent legacy
fields are allowed. Fetch the song and canonical album on natural completion, not all bookmarks.
Canonical order is stable ascending disc (missing=1), track (missing=Int.max), lowercased title;
preserve server order for exact ties and preserve explicit zero. This matches both clients' normal
album queue order. No song-ID tie-breaker or anchor migration is introduced.

A non-final completion upserts the anchor with the next canonical song, even if that song is not
in the playing queue. Final completion deletes the anchor, including one-track albums. Manual
skip, pause, restore, seek, and quit do not advance markers. Later successful listening may move
progress backward. The anchor is reserved: upsert AND final deletion may overwrite/delete another
client's ordinary music bookmark, without an ownership read. Non-music media is excluded.

Writes are serial, best effort, and account-scoped. They run from service-owned completion events
even with the screen off or Android Auto controlling playback, independently of scrobble success.
There is no persistent retry outbox: failures may leave several tracks of lag, an absent first
marker, or a stale completed album. Playback remains unaffected.

## Refresh and shared retention

Refresh once on session initialization, UI foreground, and explicit pull-to-refresh. Coalesce
overlaps. Neither Home reconstruction nor completion triggers `getBookmarks`. Group recognized
markers by nonempty album ID. Compare raw `changed ?? created ?? ""` strings descending, then
anchor-song ID descending; use this rule for representatives and retention. Present empty or
malformed timestamps remain raw. Do not normalize timestamps on Android: consistent server
formatting is assumed, and mixed offsets/precision are an accepted v1 limitation.

Publish the newest twenty albums immediately, then best-effort delete recognized compatible
markers for albums outside that twenty using only the fresh response. Never clean up from cached
Home ordering. Foreign/malformed/unknown-version markers are not housekeeping candidates. No
conditional delete exists; a concurrent client can change a row after reading, so temporary excess
and that small cross-client race are accepted. Twenty is a shared target, not a strict invariant.

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
