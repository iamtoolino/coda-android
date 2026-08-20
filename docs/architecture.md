# Architecture

## Data

`NavidromeClient` talks directly to the OpenSubsonic REST API using salted-token authentication. Screen state is held in Compose memory only and is refetched when its destination is opened or refreshed.

The API client is shared across account sessions and gives each complete metadata call a finite
20-second deadline in addition to connect/read/write timeouts. Phone UI requests run through
`AppGraph.withCurrentSession`, which captures the active client and generation before suspension and
rejects both successful and failed completions if the account changed before publication. An
unreachable VPN route, stale DNS answer, or unresponsive server therefore resolves to a screen error
instead of an indefinite loading state, and an old account cannot publish into a new session.

`HomeCoordinator` owns independent state and work for Artists, Recently Added, Recent Releases,
Recently Played, and Playlists. It starts them concurrently, shares the newest-albums request needed
by Artists and Recently Added, and publishes each result without waiting for unrelated sections.
Previously loaded section content remains visible during refresh. Network `IOException`s receive two
short backoff retries; cancellation and permanent response errors do not retry. Exhausted failures
remain local to their section and can be retried individually.

`RemoteResourceCoordinator` provides the keyed request lifecycle shared by collection and read-only
detail specializations. `RemoteCollectionCoordinator` owns Artists, Albums, and Playlists, while the
detail specialization owns Artist destination loading. Compose selects a key and renders immutable
coordinator state. Refreshing the same key retains good content; changing an album view or artist ID
clears mismatched content. Each new load cancels its predecessor and also uses a generation gate, so
even a non-cooperative obsolete request cannot publish after a newer selection.

`SearchCoordinator` owns debounced query execution independently of text-field selection and focus.
Typing waits 350 ms before issuing a request; explicit refresh starts immediately and retains the
last result for the same query. A changed query clears mismatched results. Cancellation propagates,
and a request-generation gate rejects late results from superseded queries.

`AlbumRatingCoordinator` is created once per authenticated Compose session and shared through a
scoped composition local. Its state is keyed by album ID and overlays immutable server models across
album cards and detail views. Selections publish optimistically with a monotonic per-album revision.
Each album has one serial writer and one conflated pending transaction, preserving server order while
allowing rapid changes. A failed latest transaction rolls back to the last confirmed rating and emits
a UI failure event; obsolete failures and account-session cancellation publish nothing.

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
and canonical artwork source. It shares the same encoded hero entry as the visible artwork rather
than downloading another component-specific copy. A prepare-then-commit gate prevents an obsolete
artwork request from publishing after navigation has moved elsewhere. Pending destinations retain
the last committed palette until their replacement is ready, avoiding an intermediate Brand
transition. Monochrome extraction falls back to neutral grey; missing or unreadable artwork falls
back to Brand teal.

## Artwork

Phone artwork uses four server-source buckets: 420 px for the high-volume Albums grid and playlist
thumbnails, 500 px for artist cards and rows, 600 px for Home album cards and Android Auto album
browsing, and 1200 px for heroes, Continue, Now Playing, playback metadata, and theme extraction.
Callers resolve these through one policy instead of inventing component-specific sizes.

Coil owns a decoded memory cache and a 512 MB encoded disk cache. A Navidrome cover's encoded key is
the account namespace, manual-refresh generation, cover ID, and source size, so two surfaces asking
for the same source share one disk entry. An external artist URL has one encoded entry even when it
is displayed at multiple sizes. Playback uses Android Auto's already-persistent `content://`
artwork and disables Coil disk writes for that source to avoid storing the same bytes twice.

OpenSubsonic metadata does not provide a reliable artwork revision. The Connection screen therefore
offers an explicit artwork refresh: it clears Coil memory/disk data, Android Auto artwork, and
derived theme colors, then advances the persisted generation so in-flight old requests cannot
repopulate visible stale entries. Disconnect performs the same cache cleanup for the old account.

## Playback

`PlaybackService` owns a Media3 `ExoPlayer` and `MediaLibrarySession`. `PlaybackConnection` is the
UI-side `MediaController`. It publishes stable queue/metadata separately from the 500 ms playback
progress tick, so only the visible mini-player or Now Playing controls recompose as position changes.
Queue replacement, append, and handoff restoration prepare immutable Media3 items off the main
thread and apply those mutations in user-action order.

The player uses a Media3 `SimpleCache` as a transient audio cache with explicit resource eviction
rather than a byte-based eviction policy. The queue defines its contents: whenever a queue exists, a
single cancellable worker fills the current track and next three sequentially and removes keys
outside that window. Replacing, advancing, or clearing the queue recomputes the window immediately.
The cache survives service/process recreation and is reused by the restored queue; disconnecting the
account clears its entries. Phone artwork uses Coil's separate cache described above and Android
Auto artwork uses the bounded car-artwork cache below.

Network changes do not interrupt the current track. Upcoming items are prepared off the main thread
and rewritten in one ordered Media3 batch to original or Opus stream URLs according to the active
transport.

`ScrobbleCoordinator` is owned by `PlaybackService`, beside the authoritative Media3 player used by
the phone, notification, Bluetooth, and Android Auto. A pure policy state machine gives each loaded
track a playback-occurrence identity. Media3 automatic transitions and repeat transitions submit
the outgoing occurrence, while final `STATE_ENDED` submits the last queue item. Manual transitions,
restoration, handoff, seeking, stopping, and playlist replacement never infer completion from a
saved or current position. Now-playing and completed submissions retain bounded retry behavior and
are cancelled when their account session becomes obsolete.

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
fallback when no local snapshot exists and the source for cross-client handoff. Song metadata is
rebuilt only when the Media3 timeline changes; position-only saves reuse that immutable queue and go
through a conflated background encoder/writer rather than serializing the complete queue on main.
