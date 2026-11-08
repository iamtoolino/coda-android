package dev.coda.android.playback

import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.coda.core.playback.*
import kotlinx.coroutines.*

/** Only this full-queue player is exposed to MediaSession. Stream locations stay below the seam. */
@UnstableApi
class CodaPlayer(
    val controller: PlaybackController,
    private val resolveEntry: (MediaItem) -> QueueEntry,
    /** Cover bytes for the current item's session metadata (null: the item keeps only its artwork URI). */
    artworkLoader: (suspend (String) -> ByteArray?)? = null,
    /** False for items from an ended session; such a request is dropped as a whole (it raced a sign-out). */
    private val admit: (MediaItem) -> Boolean = { true },
    /** Queue offsets whose session covers are held ([dev.coda.core.playback.BackgroundWorkPolicy.sessionCoverOffsets]). */
    private val coverOffsets: (PlaybackState) -> List<Int> = { listOf(0, 1, -1) },
    /** Whether the controller honours a seek in the current item ([currentItemSeekable]); cheap, read on Main. */
    private val seekable: (PlaybackState) -> Boolean = { true },
) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observed = controller.state.value
    private var automatic = false
    private var completionPending = false
    private val artworkData = artworkLoader?.let { CurrentArtworkData(scope, it) { invalidateState() } }

    /** Nonstandard playback data travels in session extras, whose changes are observable. */
    internal val successorEntryId: String? get() = controller.successorEntry?.id?.toString()

    init {
        scope.launch {
            controller.state.collect { state ->
                if (observed.completedPlayback?.id != state.completedPlayback?.id) completionPending = true
                val changed = observed.currentOccurrence?.item?.occurrence != state.currentOccurrence?.item?.occurrence
                automatic = changed && observed.currentOccurrence != null && state.currentOccurrence != null && completionPending
                if (changed) completionPending = false
                observed = state
                artworkData?.track(neighbourCovers(state))
                invalidateState()
                automatic = false
            }
        }
    }

    /**
     * Items of the last published queue. getState runs on Main for every state change and maps the whole queue;
     * rebuilding (and re-signing the artwork URI of) every item kept Main busy with a few hundred tracks (AA-2
     * 2026-10-30). An item is reused while its entry is unchanged and its artwork URI is still the one to publish.
     */
    private var queueItems = HashMap<QueueEntry, MediaItem>()
    private fun queueItem(entry: QueueEntry, built: HashMap<QueueEntry, MediaItem>): MediaItem {
        val cached = queueItems[entry]?.takeIf { it.mediaMetadata.artworkUri == entry.coverArtId?.let { id -> dev.coda.android.artwork.ArtworkContentProvider.uri(id) } }
        return (cached ?: mediaItem(entry)).also { built[entry] = it }
    }

    private var publishedSeekable = true
    /** Cache or capability knowledge changed without a controller state change: republish if the seek capability moved. */
    fun refreshSeekCapability() { if (seekable(controller.state.value) != publishedSeekable) invalidateState() }

    override fun getState(): State {
        dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.SESSION_STATE)
        val state = controller.state.value
        val currentSeekable = seekable(state).also { publishedSeekable = it }
        val current = shownIndex(state)
        val built = HashMap<QueueEntry, MediaItem>(state.queue.size)
        val playlist = state.queue.mapIndexed { index, entry ->
            val item = queueItem(entry, built)
            MediaItemData.Builder(entry.id).setMediaItem(item)
                .apply { if (artworkData != null && index == current) setMediaMetadata(currentMetadata(item.mediaMetadata, entry)) }
                // The current item's length is the engine's once known, so the playhead ends with the audio.
                .setDurationUs((if (index == state.currentIndex) state.displayDurationMs else entry.durationMs).let { if (it > 0) it * 1000 else C.TIME_UNSET })
                .setIsSeekable(index != state.currentIndex || currentSeekable).build()
        }
        val playbackState = playbackStateFor(state, playlistEmpty = playlist.isEmpty())
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAll(*sessionCommands(currentSeekable).toIntArray()).build())
            .setShuffleModeEnabled(false)
            .setRepeatMode(when (state.repeatMode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            })
            .setPlaylist(playlist.also { queueItems = built })
            .setCurrentMediaItemIndex(current)
            .setPlayWhenReady(state.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(playbackState)
            .setIsLoading(loadingFor(playbackState, state.isBuffering))
            .setContentPositionMs { if (state.status == "Finished") state.queue.lastOrNull()?.durationMs ?: 0 else controller.positionMs }
        state.failure?.takeIf { playbackState == Player.STATE_IDLE }?.let { builder.setPlayerError(PlaybackException("Can't play this track", null,
            when (it) {
                PlaybackFailure.NotFound -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                PlaybackFailure.Decode -> PlaybackException.ERROR_CODE_DECODING_FAILED
                PlaybackFailure.Unauthorized, is PlaybackFailure.Http -> PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            })) }
        if (automatic) builder.setPositionDiscontinuity(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0)
        return builder.build()
    }

    /**
     * What controllers that never connect through the library callback (SystemUI, Android Auto's now-playing)
     * read as the current metadata: the cover as bytes and no artwork URI, which they could not open anyway.
     * The queue items themselves keep the URI for hosts that hold a grant.
     */
    private fun currentMetadata(base: MediaMetadata, entry: QueueEntry): MediaMetadata {
        val bytes = artworkData?.bytes(entry.coverArtId)
        val builder = base.buildUpon().setArtworkUri(null)
        return MetadataGuard.guard(
            if (bytes != null) builder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build() else builder.build())
    }

    /** The item the session shows as current, also while nothing is prepared (index 0) or the queue has finished (last item). */
    private fun shownIndex(state: PlaybackState): Int =
        state.currentIndex ?: if (state.queue.isEmpty()) C.INDEX_UNSET else if (state.status == "Finished") state.queue.lastIndex else 0

    private fun neighbourCovers(state: PlaybackState): List<String> {
        val index = shownIndex(state).takeIf { it != C.INDEX_UNSET } ?: return emptyList()
        // Screen off and playing, only the current cover (one fetch per track, at the track change, from the
        // disk cache when possible); the neighbours are fetched again once the screen is on.
        return coverOffsets(state).mapNotNull { state.queue.getOrNull(index + it)?.coverArtId }.distinct()
    }

    private fun done(block: () -> Unit): ListenableFuture<*> {
        block()
        return Futures.immediateVoidFuture()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = done {
        PlaybackDiagnostics.onPlayWhenReadyHandled(playWhenReady) // records the request only; reads no player state
        if (playWhenReady) {
            // Media3 keeps the ended timeline visible; Play explicitly restarts the retained queue.
            if (controller.status == "Finished") controller.play(0) else controller.play()
        } else controller.pause()
    }
    override fun handleSetRepeatMode(repeatMode: Int) = done { controller.setRepeatMode(when (repeatMode) {
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        else -> RepeatMode.OFF
    }) }
    override fun handlePrepare() = done { controller.prepare() }
    override fun handleStop() = done { controller.stop() }
    override fun handleRelease() = done { scope.cancel(); controller.release() }
    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = done {
        if (!mediaItems.all(admit)) return@done
        // A voice request for the queue we already play must not rebuild it (no re-buffer, no jump).
        if (mediaItems.firstOrNull()?.mediaMetadata?.extras?.getBoolean(AutoLibraryCallback.KEEP_QUEUE) == true &&
            mediaItems.map { it.mediaId } == controller.queue.map { "song/${it.songId}" }) return@done
        controller.replaceQueue(resolveUniqueEntries(mediaItems, emptySet()), startIndex.takeIf { it != C.INDEX_UNSET } ?: 0,
            startPositionMs.takeIf { it != C.TIME_UNSET } ?: 0, controller.playWhenReady)
    }
    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>) = done {
        if (!mediaItems.all(admit)) return@done
        controller.insert(resolveUniqueEntries(mediaItems, controller.queue.map { it.id }.toSet()), controller.queue.getOrNull(index)?.id)
    }
    // Requests from another media controller may reuse metadata. Each occurrence still needs its own ID.
    private fun resolveUniqueEntries(items: List<MediaItem>, occupied: Set<java.util.UUID>): List<QueueEntry> {
        val used = occupied.toMutableSet()
        return items.map { item ->
            var entry = resolveEntry(item)
            while (!used.add(entry.id)) entry = entry.copy(id = java.util.UUID.randomUUID())
            entry
        }
    }
    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = done {
        val moving = controller.queue.subList(fromIndex, toIndex).map { it.id }
        val remaining = controller.queue.filterNot { it.id in moving }
        controller.move(moving, remaining.getOrNull(newIndex)?.id)
    }
    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int) = done {
        controller.remove(controller.queue.subList(fromIndex, toIndex).map { it.id })
    }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = done {
        PlaybackDiagnostics.onSeekHandled(mediaItemIndex, positionMs)
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.previous()
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.next()
            else -> controller.seek(mediaItemIndex, positionMs.takeIf { it != C.TIME_UNSET } ?: 0)
        }
    }

    companion object {
        fun mediaItem(entry: QueueEntry): MediaItem = MediaItem.Builder().setMediaId("song/${entry.songId}")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.artist)
                .setExtras(android.os.Bundle().apply { putString("coverArtId", entry.coverArtId); putString("albumId", entry.albumId)
                    putString("queueEntryId", entry.id.toString())
                    entry.albumRating?.let { putInt("albumRating", it) }
                    putString("formatName", entry.formatName)
                    entry.bitRate?.let { putInt("bitRate", it) }
                    putString("albumContinuationId", entry.albumContinuationId)
                    putBoolean("cached", entry.source is TrackSource.CachedFile)
                    putReplayGain(entry.replayGain) })
                .setArtworkUri(entry.coverArtId?.let { dev.coda.android.artwork.ArtworkContentProvider.uri(it) })
                .setAlbumTitle(entry.album).setAlbumArtist(entry.albumArtist).setReleaseYear(entry.year).setTrackNumber(entry.trackNumber).setDiscNumber(entry.discNumber)
                .setDurationMs(entry.durationMs.takeIf { it > 0 }).setIsPlayable(true).setIsBrowsable(false).build().let(MetadataGuard::guard))
            .build()
    }
}

/**
 * The shared [SeekPolicy] as the controller applies it (PlaybackController.seek ignores a positive seek in
 * an uncached transcode the server cannot start at `timeOffset`), so every session client sees the same capability as
 * the phone's seek bar. [completeInCache] must be a cheap lookup (it runs on Main for every state change).
 */
internal fun currentItemSeekable(state: dev.coda.core.playback.PlaybackState, completeInCache: (String) -> Boolean, transcodeSeeks: Boolean): Boolean {
    val source = state.currentOccurrence?.item?.source
    return SeekPolicy.isSeekable(source, source is TrackSource.Remote && completeInCache(source.songId), transcodeSeeks)
}

/** The commands the session offers; seeking inside the current item only when it is [currentSeekable]. */
internal fun sessionCommands(currentSeekable: Boolean): List<Int> = listOfNotNull(
    Player.COMMAND_SET_REPEAT_MODE,
    Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS,
    Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM,
    Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
    Player.COMMAND_GET_METADATA, Player.COMMAND_RELEASE,
    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM.takeIf { currentSeekable },
)

/**
 * The Media3 state for the controller's [state]. `State.Builder` requires an empty playlist to be IDLE or ENDED and an
 * error to come with IDLE, so `waiting` never overrides either, however the engine's flags line up.
 */
internal fun playbackStateFor(state: dev.coda.core.playback.PlaybackState, playlistEmpty: Boolean) = when {
    playlistEmpty -> Player.STATE_IDLE
    state.waiting != null -> Player.STATE_BUFFERING
    state.failure != null -> Player.STATE_IDLE
    state.status == "Finished" -> Player.STATE_ENDED
    !state.isPrepared -> Player.STATE_IDLE
    state.isBuffering -> Player.STATE_BUFFERING
    else -> Player.STATE_READY
}

/**
 * Media3 rejects a state that is loading while idle or ended (`SimpleBasePlayer.State.Builder.build`). The engine's
 * buffering flag can outlive a stop or a failure (stopping while it waits for the network), so it only counts as
 * loading while the player is buffering or ready.
 */
internal fun loadingFor(playbackState: Int, buffering: Boolean) =
    buffering && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
