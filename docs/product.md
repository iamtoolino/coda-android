# Coda product contract

Coda is a personal, album-oriented Android client for Navidrome/OpenSubsonic.

## Source of truth

- Navidrome is authoritative for albums, artists, playlists, favorites, searches, and saved queues.
- Opening a playlist always fetches `getPlaylist` and shows a loading state instead of stale membership.
- Home refreshes explicitly with pull-to-refresh.
- Artwork is cached persistently for responsive scrolling. The Connection screen provides an
  explicit artwork refresh for server-side cover or artist-image replacements.
- Library metadata is not mirrored into a local database.

## Navigation

- Home: Continue Playing, Artists, Recently Added, Recent Releases, Recently Played, and
  Playlists.
- Home renders every section immediately and fills sections independently as their requests finish.
  A slow or failed section does not block successful sections; transient network failures receive
  bounded automatic retries, and exhausted sections expose their own Retry action.
- Refresh retains the last good content while each Home section updates independently.
- Artists: alphabetical list; artist albums use oldest release first.
- Albums: server-sorted album grids publish their first page immediately and load subsequent pages
  near the visible boundary; the complete collection remains reachable without waiting for it all
  before first render.
- Multidisc album details separate discs with numbered headers and per-disc durations while keeping
  playback as one continuous album queue.
- Playlists: server order is retained exactly, with visual album headers only.
- Search: artists, albums, and tracks. Artist results that explicitly report no albums are omitted;
  servers that do not provide an album count remain compatible.

## Ratings

- Five-star album ratings are written directly to Navidrome and shown consistently on album cards
  and Album detail for the authenticated session.
- Rating changes appear immediately. Rapid changes remain interactive and resolve to the latest
  selection without allowing older server writes to finish last.
- Tapping the selected rating clears it. If the latest write fails, Coda restores the last confirmed
  rating and reports the failure.

## Visual theme

- Coda uses an artwork-led dark theme with one consistent palette across the visible phone UI.
- Brand teal (`#2B7A82`) is used before login and whenever no usable artwork-owned theme exists.
  The login Connect action uses Brand gold (`#D19433`).
- Current playback artwork owns the theme on Home, artist screens, lists, and Now Playing.
- Now Playing and Queue retain playback theme ownership for their complete visible lifetime,
  including predictive-back gestures and cancelled gestures.
- Opening an album temporarily applies that album's palette to the entire presentation, including
  its background, controls, progress indicators, and mini-player. Leaving the album restores the
  current playback palette.
- A playlist owns the theme only when the server supplies explicit playlist artwork. A playlist
  without artwork inherits playback and never uses its first track as a visual proxy.
- Artist photography never owns the palette. Artist screens inherit playback, or Brand when nothing
  is playing.
- Monochrome artwork uses neutral grey (`#8F9499`). Missing, unreadable, or otherwise unusable
  artwork uses Brand teal.
- While a replacement artwork palette is loading, Coda retains the previous valid palette rather
  than flashing through Brand.
- Every committed palette change crossfades together with the same 850 ms ease-in-out motion used
  by Coda on macOS, keeping backgrounds, surfaces, controls, progress, and ratings on one visual
  timeline.
- Ordinary phone screens use the accepted OLED Instrument treatment: true-black foundations with a
  restrained artwork-accent field, system sans typography with softened warm ink, compact
  presentation-tinted rating material, and a translucent artwork-owned mini-player.

## Playback

- Now Playing keeps the artwork and standard seek bar, gives album rating prominent direct controls,
  shows one next-track preview with the remaining queue count, and keeps codec details below the
  transport controls. The title is not prefixed with a track number; artist and album names navigate
  directly to their detail screens. The full queue remains on its dedicated screen.
- Selecting an album track queues the full album and begins at that track.
- Selecting a playlist track queues the full server playlist and begins at that track.
- The current track is highlighted in album, playlist, and queue views.
- Playback begins progressively; it must not wait for a full-file download.
- Wi-Fi/Ethernet streams the original file. Cellular requests Opus and leaves bitrate selection to the server.
- A rolling transient cache begins filling when a queue exists, with the current track followed by
  the next three. It survives process/service recreation, follows queue/current-item changes, and is
  cleared when the queue is emptied or the account disconnects.
- Coda reports a track as played only when it genuinely finishes. Automatic advance, completion of
  the final queue item, and completion before a repeat count; seeking near the end, manually
  skipping, restoring Coda, or accepting a queue handoff do not.

## Cross-client queue

- On cold start, Coda restores its own last saved Navidrome queue and position without autoplaying.
- The playback service keeps an account-scoped local snapshot so cold restoration works immediately
  after Android kills the process and does not depend on opening the phone UI or reaching Navidrome.
- A queue last written by another client is offered through the Home screen while Android is not
  playing.
- Coda saves when playback starts or resumes, when the current item changes, and every 15 seconds
  while playback continues. Paused/background state and queue edits do not trigger writes.
- Home checks for an external queue when opened, explicitly refreshed, or foregrounded while visible.
- Queue state can be continued by another Navidrome client that supports the server-side play queue
  when both clients use the same user.

## Android Auto

- Coda is discoverable as an Android Auto media app.
- The car interface exposes Artists, Added, Recent, Playlists, and search.
- Artists are grouped into `#` and `A`–`Z` buckets because Android Auto does not paginate browse nodes.
- Search results are grouped into Artists, Albums, and Songs.
- Albums and playlists can be browsed or played as complete queues.
- The Android Auto `For you` card shows up to ten recently played albums.
- Android Auto and the phone UI control the same playback session, queue, artwork, and progress.
- Private server artwork is cached and exposed to the car as a local content URI; slow uncached
  artwork must not block the car host while the phone fetches and resizes it.
- The car can cold-start the media service without opening the phone activity.

## Explicit non-goals

- Podcasts, radio, generated mixes, and discovery algorithms.
- Offline downloads or offline-library management.
- Multiple layout modes and extensive settings screens.
- Periodic foreground library polling.
