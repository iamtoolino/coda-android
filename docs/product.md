# Coda product contract

Coda is a personal, album-oriented Android client for Navidrome/OpenSubsonic.

## Source of truth

- Navidrome is authoritative for albums, artists, playlists, favorites, searches, and saved queues.
- Opening a playlist always fetches `getPlaylist` and shows a loading state instead of stale membership.
- Home refreshes explicitly with pull-to-refresh.
- Artwork may be cached persistently for responsive scrolling.
- Library metadata is not mirrored into a local database.

## Navigation

- Home: Continue Playing, Artists, Recently Added, Top Rated, Recent Releases, Recently Played, and
  Playlists.
- Artists: alphabetical list; artist albums use oldest release first.
- Albums: complete album grid ordered by date added, newest first.
- Playlists: server order is retained exactly, with visual album headers only.
- Search: artists, albums, and tracks.

## Playback

- Selecting an album track queues the full album and begins at that track.
- Selecting a playlist track queues the full server playlist and begins at that track.
- The current track is highlighted in album, playlist, and queue views.
- Playback begins progressively; it must not wait for a full-file download.
- Wi-Fi/Ethernet streams the original file. Cellular requests Opus and leaves bitrate selection to the server.
- A rolling transient cache contains the current track and next three tracks. It is cleared on a cold playback-service start.

## Cross-client queue

- On cold start, Coda restores its own last saved Navidrome queue and position without autoplaying.
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
- Android Auto and the phone UI control the same playback session, queue, artwork, and progress.
- Private server artwork is cached and exposed to the car as a local content URI.
- The car can cold-start the media service without opening the phone activity.

## Explicit non-goals

- Podcasts, radio, generated mixes, and discovery algorithms.
- Offline downloads or offline-library management.
- Multiple layout modes and extensive settings screens.
- Periodic foreground library polling.
