package dev.coda.core.playback

/**
 * How often the playback service's housekeeping runs. Before, ~22 Main/IO wake-ups per second ran for the
 * whole service life (BAT-1a S1-S5). [uiVisible] = an activity of the app is started (its UI can be on screen; false
 * with the screen off). Null: the work waits for an event instead of polling.
 */
object BackgroundWorkPolicy {
    /**
     * Engine position samples (100 ms for the app's own clock; system and Auto controllers extrapolate).
     * Kept at 100 ms with the app's UI started even while paused: without that main-thread tick the queue sheet's
     * injected flings lost their velocity on the emulators (Qu6SheetDismissDeviceTest, BAT-1b report); the screen
     * dominates that state anyway.
     */
    fun positionSampleMs(playing: Boolean, uiVisible: Boolean): Long? = when {
        uiVisible -> 100
        playing -> 1_000
        else -> null
    }

    /** Connection probe poll: it only acts while a wait is active. */
    fun probePollMs(waiting: Boolean): Long? = if (waiting) 200 else null

    /** Session extras (waiting, format, waveform, cache state): read only by the app's own UI. */
    fun extrasPollMs(uiVisible: Boolean): Long? = if (uiVisible) 250 else null

    /**
     * Waveforms are decoded only while the app's UI is started (so the screen is on), the current
     * track first; the screen signal is checked too because the UI stays "started" ~700 ms after the screen turns off; nothing is decoded or precomputed with the screen off. The bar's pending state fills in once it is ready.
     */
    fun waveformPollMs(uiVisible: Boolean, screenOn: Boolean = true): Long? = if (uiVisible && screenOn) 500 else null

    /**
     * The seek bar's motion (the loading tide, the track-change intro) runs only while the app's UI is
     * started and the screen is on; otherwise no frame is requested (the battery goal). Same signals as the decode.
     */
    fun waveformMotion(uiVisible: Boolean, screenOn: Boolean): Boolean = uiVisible && screenOn

    /**
     * THE rule for "the screen is off and Coda is playing". In that state every small request keeps the mobile
     * radio's high-power tail on (74 % of the hour), so background traffic collapses onto the track
     * changes: no periodic queue save, no now-playing scrobble, completed plays sent with the track-change queue save
     * (at most [SCROBBLE_BATCH_MS] later), session covers for the current and next track only. Callers ask through these
     * functions, never their own screen checks.
     */
    fun quiet(screenOn: Boolean, playing: Boolean): Boolean = !screenOn && playing

    /** Periodic queue position saves (15 s tick) run only with the screen on; boundaries (pause, skip, track change, exit) always save. */
    fun periodicQueueSaves(screenOn: Boolean): Boolean = screenOn

    /** "Now playing" is a best-effort hint for other clients: with the screen off it is dropped (completed plays are not). */
    fun sendsNowPlaying(screenOn: Boolean): Boolean = screenOn

    /** Completed plays wait (persisted) for the next boundary request while [quiet]. */
    fun defersScrobbles(screenOn: Boolean, playing: Boolean): Boolean = quiet(screenOn, playing)

    /** Session cover bytes: the current track and its neighbours, or current and next only while [quiet] (the next one is fetched at the track change anyway, so lock screen, notification and Auto never show a gap; the previous one is dropped). */
    fun sessionCoverOffsets(screenOn: Boolean, playing: Boolean): List<Int> = if (quiet(screenOn, playing)) listOf(0, 1) else listOf(0, 1, -1)

    /** Longest a completed play waits for a boundary request while [quiet]. */
    const val SCROBBLE_BATCH_MS = 10 * 60_000L
    /** A play persisted this soon after a boundary request still rides along with it (the radio is still up). */
    const val SCROBBLE_PIGGYBACK_MS = 5_000L

    /**
     * While [quiet], idle server connections are closed this long after the last request activity (the
     * track-change burst), so the server's TCP keep-alive probes on pooled connections stop waking the mobile radio.
     */
    const val IDLE_CONNECTION_CLOSE_MS = 20_000L

    /**
     * Idle server connections are closed ([IDLE_CONNECTION_CLOSE_MS] after the last request
     * activity) while [quiet] and whenever nothing plays, with the screen on or off.
     */
    fun closesIdleConnections(screenOn: Boolean, playing: Boolean): Boolean = quiet(screenOn, playing) || !playing

    /**
     * Paused or stopped this long, and the read-ahead has nothing running, Coda goes to deep sleep: the
     * player's stream is released and nothing is polled until Play or a real network switch. The foreground service
     * ends after the same time (Media3 `setForegroundServiceTimeoutMs`; its default is 10 min) and, BAT-6d, at the
     * release at the latest ([runsInForeground]). A shorter pause plays on at once as before. The grace counts wall time
     * ([wallClockDelay]).
     */
    const val DEEP_SLEEP_GRACE_MS = 60_000L

    /**
     * Media3's foreground decision ([required]: engaged, or its uptime timeout still pending) is overridden to
     * false once deep sleep has released the player ([asleep]) unless playback is requested ([playWhenReady]).
     */
    fun runsInForeground(required: Boolean, asleep: Boolean, playWhenReady: Boolean): Boolean =
        required && !(asleep && !playWhenReady)

    /**
     * Once deep sleep has released the player with no play requested, the playback service unstarts itself
     * (stopSelf). A bound UI or Auto keeps it alive; otherwise it is destroyed now instead of lingering started (not
     * freezable, sticky restart, a late exit save). Play afterwards goes through the media button / resumption path.
     */
    fun stopsService(asleep: Boolean, playWhenReady: Boolean): Boolean = asleep && !playWhenReady

    /** Read-ahead safety poll; queue, current item, network and session changes wake it at once. */
    fun readAheadIdleMs(uiVisible: Boolean): Long = if (uiVisible) 1_000 else 30_000
}
