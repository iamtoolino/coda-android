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
Hide the local current album even paused/restored, without expiry; keep its server marker intact.
Tapping fetches the album, validates the target, and starts the full album at that song's beginning,
remaining on Home with the mini-player. Failed lookup/missing target leaves playback untouched,
without fallback to track one or deleting the marker. Bookmark refresh never navigates.

## Verification

`./scripts/verify.sh` covers protocol, eligibility, ordering, retention, serial writes, refresh races,
failure retention, cancellation, continuation, and completion independence from scrobbling.
`./scripts/ui-test.sh <explicit-emulator-serial>` includes a credential-free plain-shelf UI test.
Live cross-client testing remains separate: finish tracks on each platform, foreground the other,
resume an album, and verify isolated/final completions and screen-off playback against a real server.
