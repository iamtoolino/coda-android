# Architecture

## Data

`NavidromeClient` talks directly to the OpenSubsonic REST API using salted-token authentication. Screen state is held in Compose memory only and is refetched when its destination is opened or refreshed.

`CredentialStore` encrypts the selected server and account with Android Keystore. `AppGraph`
reconstructs the active `NavidromeClient` after a cold start, so no private server values are compiled
into the APK. A non-secret hash of server URL and username namespaces transient caches so identifiers
from two servers can never collide. Disconnecting invalidates the session immediately and clears the
old account's cached data in the background.

## Playback

`PlaybackService` owns a Media3 `ExoPlayer` and `MediaLibrarySession`. `PlaybackConnection` is the UI-side `MediaController` and publishes a small `StateFlow` consumed by Compose.

The player uses a Media3 `SimpleCache` as a transient audio cache with explicit resource eviction
rather than a byte-based eviction policy. Playback fills the current track while reading it, and a
background `CacheWriter` fills the next three items after playback has started. Phone artwork uses
Coil's separate cache and Android Auto artwork uses the bounded car-artwork cache below.

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
