package dev.coda.android.diag

/**
 * Ids for [BatteryLog] (debug and preview builds; release compiles a no-op stub, see app/build.gradle.kts).
 * Compile-time constants only, so a release build keeps neither this object nor its names.
 */
object BatteryIds {
    // Counters (per segment in the battery log).
    const val POSITION_TICK = 0          // ExoAudioEngine 100 ms position sampler
    const val SESSION_STATE = 1          // CodaPlayer.getState builds (each MediaSession state publish)
    const val NOTIFICATION_UPDATE = 2    // MediaSessionService.onUpdateNotification
    const val SESSION_EXTRAS = 3         // session extras actually sent
    const val EXTRAS_LOOP = 4            // PlaybackService 250 ms extras loop passes
    const val PROBE_LOOP = 5             // PlaybackService 200 ms connection probe loop passes
    const val WAVEFORM_LOOP = 6          // PlaybackService 500 ms waveform loop passes
    const val CACHE_CYCLE = 7            // read-ahead worker passes (QueueCacheWorker.cycle)
    const val PREFETCH_BYTES = 8         // bytes the Prefetcher wrote
    const val PREFETCH_FILES = 9         // files the Prefetcher committed
    const val HTTP_REQUESTS = 10
    const val HTTP_BYTES_IN = 11         // response body bytes
    const val HTTP_CONNECTS = 12         // new TCP/TLS connections (radio wake-ups)
    const val HTTP_FAILURES = 13
    const val HTTP_STREAM = 14           // stream endpoint requests (player and Prefetcher)
    const val HTTP_RANGE = 15            // requests with a Range header
    const val STREAM_OPENS = 16          // player opens of a remote stream (TrackByteSource)
    const val STREAM_SKIP_BYTES = 17     // bytes read and dropped to reach an offset in an Opus transcode (re-open)
    const val QUEUE_SAVES = 18           // savePlayQueue calls (handoff)
    const val QUEUE_LOADS = 19           // getPlayQueue calls (handoff)
    const val ARTWORK_LOADS = 20         // cover byte loads (memory/disk/network)
    const val UI_PROGRESS_TICK = 21      // mini player progress loop passes
    const val WAVEFORM_DECODES = 22      // waveform decodes started
    const val CACHE_CHECKS = 23          // Maintenance ticker passes
    const val DL1_JOB_RUNS = 24          // Download job starts
    const val EXO_LOAD_ERRORS = 25       // ExoPlayer load errors (each retry too)
    const val QUEUE_COPY_WRITES = 26     // Local queue copy writes
    // Background requests not sent because there was no network (ServerGate), by kind
    const val SKIPPED_QUEUE_SAVE = 27
    const val SKIPPED_QUEUE_LOAD = 28
    const val SKIPPED_SCROBBLE = 29
    const val SKIPPED_NOW_PLAYING = 30
    const val SKIPPED_PING = 31
    const val SKIPPED_ALBUM_RESUME = 32
    // Artwork provider reads (Android Auto, lock screen, system media controls, Bluetooth cover art)
    const val PROVIDER_LOCAL_COVERS = 33 // served from a copy already on the phone (any size, scaled down)
    const val PROVIDER_COVER_FETCHES = 34 // no copy of the cover on the phone: the fetcher was asked (network unless it raced in)
    const val COUNTERS = 35

    // Wake lock / lock holders (held time per segment).
    const val LOCK_PLAYBACK = 0          // Coda:playback partial wake lock (AudioSessionGuard)
    const val LOCK_WIFI = 1              // Coda:stream Wi-Fi lock
    const val LOCK_DL1_JOB = 2           // JobScheduler holds a wake lock while the job runs
    const val LOCKS = 3

    // Playback states.
    const val PLAYBACK_IDLE = 0
    const val PLAYBACK_PLAYING = 1
    const val PLAYBACK_PAUSED = 2
    const val PLAYBACK_BUFFERING = 3
    const val PLAYBACK_WAITING = 4

    // Events.
    const val EV_SERVICE_CREATE = 0
    const val EV_SERVICE_DESTROY = 1
    const val EV_FOREGROUND = 2          // value 1: foreground required, 0: not
    const val EV_JOB_START = 3
    const val EV_JOB_STOP = 4            // value 1: stopped by the system
    const val EV_JOB_FINISH = 5          // value 1: reschedule
    const val EV_MARK = 6                // test/run marker with a number
    const val EV_ENGINE_SOURCE = 8       // the engine built a media source; value 1: a cached file, 0: a remote stream
    const val EV_STREAM_REOPEN = 7       // a remote stream opened at a byte offset > 0; value: the offset in KiB
}
