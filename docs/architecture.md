# Architecture

## Data

`NavidromeClient` talks directly to the OpenSubsonic REST API using salted-token authentication.
Screen state is held in Compose memory only. Ordinary destinations refetch when opened or refreshed;
Home retains its coordinator for the authenticated Compose session so returning from a detail screen
does not reload every shelf, while pull-to-refresh still requests fresh server content.

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
Home album shelves request and retain at most twelve items. Retrying Artists reuses an already loaded
Recently Added result and only refetches it when none is available; the complete portable artist
catalogue is still requested to preserve correct artist metadata and imagery.

`RemoteResourceCoordinator` provides the keyed request lifecycle shared by ordinary collections and
read-only detail specializations. `RemoteCollectionCoordinator` owns Artists and Playlists, while the
detail specialization owns Artist, Album, and Playlist destination loading. The Albums screen uses an
album-specific paging coordinator: it publishes the first server-sorted page immediately, appends the
next page near the visible boundary, and treats a partial page as the end. Compose selects a key and
renders immutable coordinator state. Refreshing the same key retains good content; changing an album
view or artist ID clears mismatched content. Each new load cancels its predecessor and also uses a
generation gate, so even a non-cooperative obsolete request cannot publish after a newer selection.

`SearchCoordinator` owns debounced query execution independently of text-field selection and focus.
Typing waits 350 ms before issuing a request; explicit refresh starts immediately and retains the
last result for the same query. A changed query clears mismatched results. Cancellation propagates,
and a request-generation gate rejects late results from superseded queries.

`AlbumRatingCoordinator` is created once per authenticated Compose session and shared through a
scoped composition local. Its state is keyed by album ID and overlays immutable server models across
album cards, Album detail, and Now Playing. Now Playing resolves the current album's server rating
through the session-safe detail lifecycle before feeding the shared coordinator. Selections publish
optimistically with a monotonic per-album revision.
Each album has one serial writer and one conflated pending transaction, preserving server order while
allowing rapid changes. A failed latest transaction rolls back to the last confirmed rating and emits
a UI failure event; obsolete failures and account-session cancellation publish nothing.

`CredentialStore` encrypts the selected server and account with Android Keystore. `AppGraph`
reconstructs the active `NavidromeClient` after a cold start, so no private server values are compiled
into the APK. A non-secret hash of server URL and username namespaces transient caches so identifiers
from two servers can never collide. Disconnecting invalidates the session immediately and clears the
old account's cached data in the background. Transient-audio deletion is recorded in a durable
application-owned pending-cleanup ledger before work starts. An active playback service performs the
deletion and acknowledges it only after success; an interrupted or inactive-service request is
replayed the next time the service starts.

## Album continuation

`AppGraph` owns one `AlbumResumeCoordinator` and Main-dispatched supervisor scope per authenticated
account, independent of Compose and service recreation. Session rotation cancels that scope, clears
its presentation, and guards every request through `withCurrentSession` plus the captured generation.
`PlaybackService` feeds natural completions from the existing scrobble occurrence policy before and
independently of scrobble HTTP submission. The coordinator resolves song eligibility and canonical
album membership, then serializes one bookmark mutation per completion. No durable outbox exists.

Session initialization, UI foregrounding, and Home pull-to-refresh coalesce bookmark reads. Home
only observes the capped item flow, including the current album; returning to Home does not fetch
bookmarks. Cards navigate to the existing keyed album detail coordinator. Detail observes bookmark
and current-entry state, resolves the target from the loaded canonical page, and renders a continuous
suffix treatment. Its Play/Append actions send that suffix through `PlaybackConnection` using the
existing account-guarded queue mutation path; there is no separate asynchronous continuation fetch.
Failures retain good shelf data without delaying other shelves. Successful writes update
the presentation provisionally, never as a basis for cleanup. Fresh snapshots alone drive recent-20
housekeeping. A revision gate rejects stale refresh results; a shared write mutex prevents cleanup
from racing local progress writes. The protocol's unavoidable cross-client deletion race remains.
See [album-resume.md](album-resume.md). Bookmark JSON contains no playback position or duplicated
album metadata, and is never logged. No debug lab or new dependency is involved.

## Theme ownership

`CodaThemeRouter` resolves semantic theme requests before the root Compose theme is applied. It
chooses a foreground album or explicitly illustrated playlist first, otherwise current playback,
otherwise Brand. Artist destinations and playlists without explicit artwork request inheritance
rather than extracting a new palette. Because the resolved theme wraps the complete phone
presentation, destination controls, backgrounds, progress indicators, and the persistent
mini-player always use the same palette. While Now Playing or Queue remains in Navigation's visible
entries, playback temporarily has absolute priority; a destination revealed by predictive back can
reclaim ownership only after the playback presentation has fully disappeared.

Artwork extraction is asynchronous and cached by account namespace, extraction algorithm version,
and canonical artwork source. It shares the same encoded hero entry as the visible artwork rather
than downloading another component-specific copy. A prepare-then-commit gate prevents an obsolete
artwork request from publishing after navigation has moved elsewhere. Pending destinations retain
the last committed palette until their replacement is ready, avoiding an intermediate Brand
transition. Once committed, every routed Material color interpolates through one synchronized
850 ms ease-in-out transition, so backgrounds, surfaces, controls, progress, ratings, and their
foreground colors cannot animate on independent timelines.
The perceptual extractor selects a well-supported artwork hue family, then constrains lightness and
chroma to a reliable UI range. Monochrome artwork produces a restrained derived neutral; missing or
unreadable artwork falls back to Brand teal.

## Artwork

Album artwork is resolved before song artwork: an explicit canonical cover from a loaded album
page, then the song's album ID, then its own cover ID or song ID when no album is known. A song's
`coverArt` may identify embedded track artwork and must not override album identity. Loaded album
pages attach `canonicalAlbumCoverArt` to their songs; playback metadata and local snapshots preserve
that distinction. Legacy snapshots without it use the album-ID fallback. Playback, Android Auto,
queue handoff presentation, and bookmark-derived Home cards share this policy without per-song
album requests.

Phone artwork uses four server-source buckets: 420 px for the high-volume Albums grid and playlist
thumbnails, 500 px for artist cards and rows, 600 px for Home album cards, and 1200 px for phone
heroes, Continue, Now Playing, and theme extraction. Android Auto requests 320 px browse thumbnails
and 600 px playback metadata artwork.
Callers resolve these through one policy instead of inventing component-specific sizes.

Coil owns a decoded memory cache and a 10 GB encoded disk cache. A Navidrome cover's encoded key is
the account namespace, manual-refresh generation, cover ID, and source size, so two surfaces asking
for the same source share one disk entry. An external artist URL has one encoded entry even when it
is displayed at multiple sizes. Playback uses Android Auto's already-persistent `content://`
artwork and disables Coil disk writes for that source to avoid storing the same bytes twice.
Phone artwork uses a dedicated OkHttp client with 10-second connect and 15-second read timeouts.
Transient network-I/O failures receive two cancellation-aware retries after 250 ms and 750 ms;
HTTP errors, decode errors, and local sources do not retry. A successful HTTP response that explicitly
declares a zero-byte body is rejected before Coil can commit it, so the same bounded retry policy
handles transient empty responses instead of poisoning the encoded disk cache.

OpenSubsonic metadata does not provide a reliable artwork revision. The Connection screen therefore
offers an explicit artwork refresh: it clears Coil memory/disk data, Android Auto artwork, and
derived theme colors, then advances the persisted generation so in-flight old requests cannot
repopulate visible stale entries. Disconnect performs the same cache cleanup for the old account.

## Playback

`PlaybackService` owns a Media3 `ExoPlayer` and `MediaLibrarySession`. `PlaybackConnection` is the
UI-side `MediaController`. It publishes stable queue/metadata separately from the 500 ms playback
progress tick, so only the visible mini-player or Now Playing controls recompose as position changes.
The phone bridge reconnects with bounded exponential backoff after an initial connection failure or
session disconnection; this affects UI availability only, never service-owned playback behavior.
Queue replacement, append, and handoff restoration prepare immutable Media3 items off the main
thread and apply those mutations in user-action order.

The service also owns shared Navidrome queue saving beside the authoritative player. A conflated
serial coordinator observes service-side playback intent and item transitions, captures immutable
queue snapshots, writes every 15 seconds only while genuinely playing, and invalidates pending work
on pause or account change. Android Auto, notifications, Bluetooth, and media-button cold starts use
this path without depending on the phone `PlaybackConnection`.

Playable Media3 items carry both album and artist IDs in their metadata, and the local playback
snapshot preserves those IDs across service/process restoration. Now Playing navigation therefore
uses the same restored authoritative queue metadata rather than resolving names back to library IDs.
Song presentation centrally prefers the server's nonblank `displayAlbumArtist` over its track-level
`artist`. The canonical value is written into Media3 metadata so the phone UI, queue, notification,
lock screen, restored session, and Android Auto do not independently choose different artist labels.

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

`PlaybackService` is exported as both a Media3 `MediaLibraryService` and a legacy-compatible `MediaBrowserService`. `CodaMediaLibraryCallback` exposes four car-safe roots: alphabetically bucketed artists, recently added albums, recently played albums, and playlists. A separate playable recommendation root supplies up to ten recently played albums to the Android Auto `For you` card, including legacy hosts that do not set the suggested-content root hint. Each node returns its complete intended contents because Android Auto does not paginate media-browser children. Search, album playback, and playlist playback resolve library IDs back into the same network-aware `MediaItem` factory used by the phone UI.

The exported session authorizes controllers before exposing commands. Coda's own controller and the
Media3-recognized Android Auto companion receive full library and playback access. The exact
platform-trusted Google recommendation broker receives library browsing plus transport controls,
and may select a published Coda library item so the widget can start it, but cannot otherwise mutate
the queue or submit a direct playable URI. Media notification and other user-trusted system controllers receive
transport and playback-state access without library browsing or queue mutation. Untrusted
applications are rejected. The service remains exported so Android Auto, system controls, media
buttons, and playback resumption can cold-start it.

Android Auto supplies the driving UI. Coda supplies browse metadata, artwork, the playback queue, and the shared media session. The service can be cold-started by the car host without launching `MainActivity`.

The session does not push periodic position corrections while a track is playing. Controllers
extrapolate progress from the published position and playback speed, while Play/Pause, seeks,
buffering, errors, and item transitions still publish immediately. This prevents Android Auto's
queue screen from repeatedly re-anchoring itself to the current item during manual scrolling.

Remote artwork is represented to Android Auto as a local `content://` URI. `CarArtworkProvider`
downloads it through Coda's authenticated phone connection and keeps a bounded disk cache, which
allows private and VPN-only servers to work on physical car hosts. Cache misses run on two bounded
workers; after a short fast-path wait, the provider returns a local pipe that streams the completed
file instead of blocking the Binder call or returning a transient missing image. Cache byte usage is
accounted incrementally, so a full LRU walk occurs only when the 128 MB limit is exceeded. A single
compressed response remains limited to 16 MB.

## Queue handoff

The OpenSubsonic `getPlayQueue` and `savePlayQueue` endpoints carry song order, current item, and
position. Saving uses form POST to avoid URL-length limits on long queues. A conflated serial worker
ensures an older save cannot finish after a newer one, while keeping only the latest pending state.
Queue writes identify the installation as `Coda on <device name>` while other API calls retain the
stable `CodaAndroid` identifier. Android writes once when playback starts/resumes or changes item and
every 15 seconds while playing; pause, background, and queue-edit events do not write. Pending writes
are cancelled when playback stops. This writer belongs to `PlaybackService`; the phone controller
only renders state and issues user-requested player commands.

The activity performs an opportunistic queue read whenever the phone UI enters the foreground; Home
also reads when opened or explicitly refreshed. If a foreground read finds a queue written by
another client before the user interacts or navigates, the navigation stack is popped directly to
Home so intermediate detail screens are never exposed during the transition. A cold-start queue
last written by Android is restored paused. A non-empty queue written by another client is exposed
separately as a Continue candidate and never replaces the local paused queue until the user accepts
it. Queue-read failures do not fail Home's library content.

The playback service also persists an account-scoped local queue snapshot containing song metadata,
the current index, and position. It restores that snapshot synchronously before publishing a new
MediaSession, so the phone UI, notifications, Bluetooth, and Android Auto all see the same paused
queue after process death without waiting for the activity or network. The server queue remains the
fallback when no local snapshot exists and the source for cross-client handoff. Song metadata is
rebuilt only when the Media3 timeline changes; position-only saves reuse that immutable queue and go
through a conflated background encoder/writer rather than serializing the complete queue on main.
Server restoration results carry their captured account generation through to the service. Account
invalidation cancels and invalidates pending restoration, and the service rechecks the generation on
the main thread immediately before mutating or preparing the player. Every playable item also carries
that generation; the service rejects an obsolete or unidentified timeline before queue saving,
snapshot publication, preparation, or prefetch can continue.

Now Playing reads selected audio track formats through the MediaController, supplied by the service
player. Requested stream variants and source metadata are not evidence of the received format.
Track changes update the observed codec, sample rate, channel count, and known average bitrate;
source measurements remain separate, including after cold restoration.

Connection diagnostics use the existing keyed remote-resource coordinator and AppGraph.withCurrentSession for a bounded ping. Optional response envelope metadata supplies server/API versions and OpenSubsonic support; unknown fields remain unknown. BuildConfig embeds Git revision, exact tag when available, and source state at build time. No credentials or server details are embedded in the build. The Client row identifies the ordinary API client, not the device-specific queue writer. Missing Git executables yield unavailable build metadata instead of blocking configuration.
