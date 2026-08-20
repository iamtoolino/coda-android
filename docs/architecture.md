# Architecture

## Data

`NavidromeClient` talks directly to the OpenSubsonic REST API using salted-token authentication. Screen state is held in Compose memory only and is refetched when its destination is opened or refreshed.

The API client is shared across account sessions and gives each complete metadata call a finite
20-second deadline in addition to connect/read/write timeouts. Phone UI requests run through
`AppGraph.withCurrentSession`, which captures the active client and generation before suspension and
rejects both successful and failed completions if the account changed before publication. An
unreachable VPN route, stale DNS answer, or unresponsive server therefore resolves to a screen error
instead of an indefinite loading state, and an old account cannot publish into a new session.

`CredentialStore` encrypts the selected server and account with Android Keystore. `AppGraph`
reconstructs the active `NavidromeClient` after a cold start, so no private server values are compiled
into the APK. A non-secret hash of server URL and username namespaces transient caches so identifiers
from two servers can never collide. Disconnecting invalidates the session immediately and clears the
old account's cached data in the background.

## Theme ownership

`CodaThemeRouter` resolves semantic theme requests before the root Compose theme is applied. It
chooses a foreground album or explicitly illustrated playlist first, otherwise current playback,
otherwise Brand. Artist destinations and playlists without explicit artwork request inheritance
rather than extracting a new palette. Because the resolved theme wraps the complete phone
presentation, destination controls, backgrounds, progress indicators, and the persistent
mini-player always use the same palette.

Artwork extraction is asynchronous and cached by account namespace, extraction algorithm version,
and canonical semantic identity. A prepare-then-commit gate prevents an obsolete artwork request
from publishing after navigation has moved elsewhere. Pending destinations retain the last committed
palette until their replacement is ready, avoiding an intermediate Brand transition. Monochrome
extraction falls back to neutral grey; missing or unreadable artwork falls back to Brand teal.

## Playback

`PlaybackService` owns a Media3 `ExoPlayer` and `MediaLibrarySession`. `PlaybackConnection` is the UI-side `MediaController` and publishes a small `StateFlow` consumed by Compose.

The player uses a Media3 `SimpleCache` as a transient audio cache with explicit resource eviction
rather than a byte-based eviction policy. The queue defines its contents: whenever a queue exists, a
single cancellable worker fills the current track and next three sequentially and removes keys
outside that window. Replacing, advancing, or clearing the queue recomputes the window immediately.
The cache survives service/process recreation and is reused by the restored queue; disconnecting the
account clears its entries. Phone artwork uses Coil's separate cache and Android Auto artwork uses
the bounded car-artwork cache below.

Network changes do not interrupt the current track. Upcoming items are rewritten to original or Opus stream URLs according to the active transport.

## Android Auto

`PlaybackService` is exported as both a Media3 `MediaLibraryService` and a legacy-compatible `MediaBrowserService`. `CodaMediaLibraryCallback` exposes four car-safe roots: alphabetically bucketed artists, recently added albums, recently played albums, and playlists. Each node returns its complete intended contents because Android Auto does not paginate media-browser children. Search, album playback, and playlist playback resolve library IDs back into the same network-aware `MediaItem` factory used by the phone UI.

Android Auto supplies the driving UI. Coda supplies browse metadata, artwork, the playback queue, and the shared media session. The service can be cold-started by the car host without launching `MainActivity`.

Remote artwork is represented to Android Auto as a local `content://` URI. `CarArtworkProvider`
downloads it through Coda's authenticated phone connection and keeps a bounded disk cache, which
allows private and VPN-only servers to work on physical car hosts. The car cache is limited to 128 MB
in total and 16 MB for any single compressed response.

## Queue handoff

The OpenSubsonic `getPlayQueue` and `savePlayQueue` endpoints carry song order, current item, and
position. Saving uses form POST to avoid URL-length limits on long queues. A conflated serial worker
ensures an older save cannot finish after a newer one, while keeping only the latest pending state.
Queue writes identify the installation as `Coda on <device name>` while other API calls retain the
stable `CodaAndroid` identifier. Android writes once when playback starts/resumes or changes item and
every 15 seconds while playing; pause, background, and queue-edit events do not write. Pending writes
are cancelled when playback stops.

Home performs opportunistic queue reads when it opens, refreshes, or returns to the foreground. A
cold-start queue last written by Android is restored paused. A non-empty queue written by another
client is exposed separately as a Continue candidate and never replaces the local paused queue until
the user accepts it. Queue-read failures do not fail Home's library content.

The playback service also persists an account-scoped local queue snapshot containing song metadata,
the current index, and position. It restores that snapshot synchronously before publishing a new
MediaSession, so the phone UI, notifications, Bluetooth, and Android Auto all see the same paused
queue after process death without waiting for the activity or network. The server queue remains the
fallback when no local snapshot exists and the source for cross-client handoff.
