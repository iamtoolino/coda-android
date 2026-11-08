package dev.coda.android.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.*
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import dev.coda.core.playback.*
import java.io.IOException
import java.nio.ByteBuffer

/** Called on the source loader, at open time. The resulting URL never enters a MediaItem. */
fun interface RemoteStreamResolver {
    fun resolve(source: TrackSource.Remote): Uri
    /**
     * Whether the server starts a transcode at `timeOffset` (OpenSubsonic `transcodeOffset`). Called on the
     * source loader before the first open of a stream that would use it; may block (one request per session).
     */
    fun supportsTranscodeOffset(): Boolean = false
    /** [supportsTranscodeOffset]'s answer if already known, without asking (any thread); null while unknown. */
    fun knownTranscodeOffset(): Boolean? = null
}

/**
 * Records which period each audio stream belongs to. Media3 passes the stream's renderer offset and period
 * when it enables a renderer or replaces its stream; the renderer later applies that offset to the sink when the
 * stream's output starts, so ItemAudioSink can tell which period it is outputting.
 */
@UnstableApi
internal class StreamRecordingRenderer(renderer: Renderer, private val audio: ItemAudioState) : ForwardingRenderer(renderer) {
    override fun enable(configuration: RendererConfiguration, formats: Array<out Format>, stream: SampleStream, positionUs: Long,
        joining: Boolean, mayRenderStartOfStream: Boolean, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
        audio.recordStream(offsetUs, mediaPeriodId.periodUid)
        audio.rendererResetUs = startPositionUs // BaseRenderer.enable resets to it.
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }
    override fun replaceStream(formats: Array<out Format>, stream: SampleStream, startPositionUs: Long, offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId) {
        audio.recordStream(offsetUs, mediaPeriodId.periodUid)
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }
    // OpusCodecAdapter trims the decoder run that follows to this position.
    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        audio.rendererResetUs = positionUs
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }
}

/** Records per-occurrence trimming inputs from the sink configuration and timestamps. */
@UnstableApi
internal class ItemAudioSink(sink: AudioSink, private val audio: ItemAudioState) : ForwardingAudioSink(sink) {
    private var applied: AudioSink.AudioSinkConfig? = null
    private var offsetSinceConfigure = false
    // The period of the stream whose offset the renderer applied last: the stream the sink is outputting (stream
    // identity from StreamRecordingRenderer, not the configuration's label, which names the period being read).
    private var outputPeriodUid: Any? = null
    // The load (ItemAudioState.loadedFirst) whose first configure was attributed; at most one remap per load.
    private var attributedLoad: Any? = null
    // An AAC first configure after a load that forOutput moved to the first item: its own period id and format.
    private var remappedFrom: MediaSource.MediaPeriodId? = null
    private var remappedFormat: Format? = null
    override fun configure(requested: AudioSink.AudioSinkConfig) {
        // For a remapped item: its decoder's second, metadata-only output format arrives with the same
        // (successor's) label and no new stream offset. A new offset means the successor itself started.
        // Bounded to measured decoder behaviour: only c2.android.aac.decoder repeats its output format,
        // and an Opus successor may configure before its own offset (ID3). For any other format an identical,
        // successor-labelled configure without an offset is therefore taken as the successor (sequence test).
        if (remappedFrom != null && !offsetSinceConfigure && requested.mediaPeriodId == remappedFrom &&
            requested.format == remappedFormat) return
        if (offsetSinceConfigure) remappedFrom = null
        val config = forOutput(requested)
        // Some decoders (c2.android.aac.decoder from the Google Play media module) report
        // a second output format a few buffers into a stream, differing only in codec
        // metadata (aac-target-ref-level). Media3 then re-configures the sink with the same
        // PCM format, and DefaultAudioSink's trimming re-applies the encoder delay to the
        // next buffer: 1024 audible frames are lost. The same period's identical
        // configuration has nothing to change, so it must not re-trim.
        // A successor whose first configure Media3 labelled late is told apart by forOutput; a repeated
        // window has a new window sequence number.
        val last = applied
        if (last != null && config.mediaPeriodId != null && last.mediaPeriodId == config.mediaPeriodId &&
            last.format == config.format && last.outputChannelMapping == config.outputChannelMapping &&
            last.preferredBufferSizeOverride == config.preferredBufferSizeOverride) return
        val uid = config.mediaPeriodId?.periodUid
        if (uid != null && !config.timeline.isEmpty) {
            val period = config.timeline.getPeriodByUid(uid, Timeline.Period())
            val window = config.timeline.getWindow(period.windowIndex, Timeline.Window())
            val item = window.mediaItem.localConfiguration?.tag as? EngineItem
            if (item != null) {
                audio.encoderDelayFrames[item.occurrence] = config.format.encoderDelay
                // ItemAudioProcessor drops MP3 padding the sink releases at end of stream.
                audio.encoderPaddingFrames[item.occurrence] = config.format.encoderPadding
            }
            // Edit-list AAC: Media3 trims the edit's start but not its exact end, so cap at the audible length
            // the boxes give, as long as the delay is the edit's. iTunSMPB alone already supplies the
            // exact padding. Without box data, an edit without padding falls back to the duration.
            if (item != null && audio.mimeTypes[item.occurrence] == MimeTypes.AUDIO_AAC) {
                val edit = audio.mp4EditFrames[item.occurrence]
                val rate = config.format.sampleRate
                if (edit != null && edit.delayAt(rate) == config.format.encoderDelay.toLong()) {
                    audio.endFrames[item.occurrence] = edit.audibleAt(rate)
                } else if (config.format.encoderPadding == 0 && window.durationUs != C.TIME_UNSET) {
                    audio.endFrames[item.occurrence] = Math.round(window.durationUs * rate / 1e6)
                }
            }
        }
        // Media3 normally applies a new stream's offset before its configuration. A decoder that
        // labels output with audible time (ID3) reverses that at gapless transitions.
        audio.outputStreamOffsetPending = last != null && last.mediaPeriodId != config.mediaPeriodId &&
            !offsetSinceConfigure
        offsetSinceConfigure = false
        audio.playingToEndOfStream = false
        super.configure(config)
        applied = config
    }

    /**
     * The configuration for the item whose audio the sink outputs, with its gapless counts at the output rate.
     *
     * Media3 labels a configuration with the renderer's *reading* period. When an item has only its last
     * access units left (a resume there), the renderer already reads the successor when the item's first output
     * format arrives, so the first configuration after a load carries the successor's period id. The first output
     * after a load always belongs to the playlist's first period (ExoAudioEngine loads the current item at index 0),
     * so that is the period used; the successor's own configure follows. Only the first configure from the loaded
     * playlist, and only while a single stream offset was applied: a renderer switch (MP3 to Opus) also resets the
     * sink, and its configure is for the period it names.
     *
     * HE-AAC with implicit SBR. The container describes the AAC core (for example 22.05 kHz) and Media3
     * gives its gapless counts (iTunSMPB, edit list) in core samples, but the decoder outputs twice the rate.
     * Media3 trimmed them as output frames, so half the priming and padding played at every transition (+3,400 and
     * +2,628 frames measured with Apple HE-AAC). An edit list is converted from its own units straight to the
     * output rate (no rounding to core samples); iTunSMPB counts are multiplied by the rate ratio.
     */
    private fun forOutput(requested: AudioSink.AudioSinkConfig): AudioSink.AudioSinkConfig {
        val timeline = requested.timeline
        var periodId = requested.mediaPeriodId
        val loaded = audio.loadedFirst
        if (loaded != null && loaded !== attributedLoad && periodId != null && !timeline.isEmpty &&
            timeline.getWindow(0, Timeline.Window()).mediaItem === loaded) {
            // The first configure from the playlist just loaded (an older playlist's late configure is not).
            attributedLoad = loaded
            // Only while the stream being output is the load's first period. Had the first item produced no output,
            // Media3 would already have applied the successor's stream, and its configure keeps its own period.
            if (outputPeriodUid != null && outputPeriodUid == timeline.getUidOfPeriod(0) &&
                timeline.getIndexOfPeriod(periodId.periodUid) > 0) {
                val first = timeline.getWindow(0, Timeline.Window()).mediaItem.localConfiguration?.tag as? EngineItem
                remappedFrom = if (first != null && audio.mimeTypes[first.occurrence] == MimeTypes.AUDIO_AAC) periodId else null
                remappedFormat = requested.format
                periodId = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
            }
        }
        var format = requested.format
        val item = periodId?.let { id ->
            if (timeline.isEmpty) null else timeline.getWindow(timeline.getPeriodByUid(id.periodUid, Timeline.Period()).windowIndex,
                Timeline.Window()).mediaItem.localConfiguration?.tag as? EngineItem
        }
        val source = item?.let { audio.sourceSampleRates[it.occurrence] }
        val output = format.sampleRate
        if (item != null && audio.mimeTypes[item.occurrence] == MimeTypes.AUDIO_AAC && source != null && source > 0 && output > source) {
            val edit = audio.mp4EditFrames[item.occurrence]
            format = if (edit != null && edit.delayAt(source) == format.encoderDelay.toLong()) {
                // The cap (endFrames, the exact audible length) removes the padding; the trimmer holds none.
                format.buildUpon().setEncoderDelay(edit.delayAt(output).toInt()).setEncoderPadding(0).build()
            } else if (output % source == 0) {
                format.buildUpon().setEncoderDelay(format.encoderDelay * (output / source))
                    .setEncoderPadding(format.encoderPadding * (output / source)).build()
            } else format
        }
        if (periodId === requested.mediaPeriodId && format === requested.format) return requested
        @Suppress("DEPRECATION")
        return AudioSink.AudioSinkConfig.Builder(format).setPreferredBufferSizeOverride(requested.preferredBufferSizeOverride)
            .setOutputChannelMapping(requested.outputChannelMapping).setTimeline(timeline).setMediaPeriodId(periodId).build()
    }
    override fun playToEndOfStream() {
        audio.playingToEndOfStream = true
        super.playToEndOfStream()
    }
    override fun flush() {
        audio.playingToEndOfStream = false
        super.flush()
    }
    override fun reset() {
        audio.playingToEndOfStream = false
        applied = null
        offsetSinceConfigure = false
        outputPeriodUid = null
        remappedFrom = null
        audio.outputStreamOffsetPending = false
        super.reset()
    }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        audio.outputStreamOffsetUs = outputStreamOffsetUs
        audio.outputStreamOffsetPending = false
        offsetSinceConfigure = true
        outputPeriodUid = audio.periodOfStream(outputStreamOffsetUs)
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        audio.presentationTimeUs = presentationTimeUs
        audio.playingToEndOfStream = false
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
}

/** A stream interruption swaps to the cached copy only when less than this much audio is buffered. */
private const val SWAP_BELOW_BUFFER_MS = 2_000L

@UnstableApi
class ExoAudioEngine(
    context: Context,
    private val remoteStreams: RemoteStreamResolver,
) : AudioEngine {
    override val name = "Media3 ExoPlayer"
    override var listener: AudioEngine.Listener? = null
    private val audio = ItemAudioState()
    private val handler = Handler(Looper.getMainLooper())
    private val player: ExoPlayer
    private var released = false
    private val tracker = PositionTracker()
    private val sources = mutableMapOf<String, EngineItem>()
    private var decoderName: String? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            dev.coda.android.diag.BatteryLog.count(dev.coda.android.diag.BatteryIds.POSITION_TICK)
            sample()
            handler.removeCallbacks(this)
            // Without the app's UI, no samples while not playing (the position stands; every play, pause, seek
            // and discontinuity samples through onEvents and restarts this) and 1 s instead of 100 ms while playing:
            // the reported clock runs free between samples, so system and Auto controllers see no difference.
            BackgroundWorkPolicy.positionSampleMs(player.isPlaying, fastSamples)?.let { handler.postDelayed(this, it) }
        }
    }
    private var fastSamples = true
    /** Main thread. */
    fun setFastPositionSamples(fast: Boolean) {
        if (fastSamples == fast) return
        fastSamples = fast
        ticker.run()
    }

    /** [rebase]: the position just moved on purpose (seek, load, discontinuity); what was reported before no longer binds. */
    private fun sample(rebase: Boolean = false) {
        val duration = player.duration
        tracker.sample(player.currentPosition, SystemClock.elapsedRealtime(), player.isPlaying,
            duration.takeIf { it != C.TIME_UNSET }, player.currentMediaItem?.mediaId, rebase)
    }
    /** A load or seek whose start the player has not settled yet (its first ready and playing state after it). */
    private val pendingStart = PendingStart()
    private fun settleStart() {
        if (!pendingStart.take(player.playbackState == Player.STATE_READY, player.isPlaying)) return
        val duration = player.duration
        tracker.settle(player.currentPosition, SystemClock.elapsedRealtime(), player.isPlaying,
            duration.takeIf { it != C.TIME_UNSET }, player.currentMediaItem?.mediaId)
    }

    init {
        PrivateMedia3Logger.install() // Must precede player construction and every source load.
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val processors = mutableListOf<androidx.media3.common.audio.AudioProcessor>(ItemAudioProcessor(audio))
                processors += PlaybackDiagnostics.processors()
                return ItemAudioSink(DefaultAudioSink.Builder(context)
                    .setAudioProcessors(processors.toTypedArray()).build(), audio)
            }
            override fun createRenderers(eventHandler: Handler, videoRendererEventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                audioRendererEventListener: AudioRendererEventListener, textRendererOutput: androidx.media3.exoplayer.text.TextOutput,
                metadataRendererOutput: androidx.media3.exoplayer.metadata.MetadataOutput): Array<Renderer> =
                super.createRenderers(eventHandler, videoRendererEventListener, audioRendererEventListener, textRendererOutput, metadataRendererOutput)
                    .map { if (it.trackType == C.TRACK_TYPE_AUDIO) StreamRecordingRenderer(it, audio) else it }.toTypedArray()
            override fun getCodecAdapterFactory(): androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory {
                val codecs = super.getCodecAdapterFactory()
                return androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory { configuration ->
                    val codec = codecs.createAdapter(configuration)
                    if (OpusCodecAdapter.isOpus(configuration.format)) OpusCodecAdapter(codec, configuration.format, audio) else codec
                }
            }
        }
        // Our MP3-only LGPL extension rejects every MIME type except audio/mpeg.
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        player = ExoPlayer.Builder(context, renderers).setLooper(Looper.getMainLooper()).build()
        // PCM processors are mandatory; focus/noisy/locks belong to AudioSessionGuard.
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        player.setHandleAudioBecomingNoisy(false)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // A reopen still pending for the old track is moot (its occurrence guard drops it), and its target
                    // must not read as the new track's position.
                    // Never a fade across a gapless change; a faded pause under way pauses now.
                    reopen.clear(); silence.completePause(); silence.end(SeekSilence.Exit.TRACK_CHANGED)
                    current()?.let { emit(EngineEvent.Advanced(it.occurrence)) }
                    emitState() // Advanced resets controller actual-playing state even if Exo's value is unchanged.
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Every discontinuity Exo reports is a real move of the position (seek, transition, removal).
                sample(rebase = true)
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.size() == 1 && events.contains(Player.EVENT_VOLUME_CHANGED)) return // Fade steps.
                if (player.isPlaying) silence.landed() // A held seek plays its new position: fade in.
                // Before the state goes out: the controller's ready (playing) event then carries where the audio really is.
                settleStart()
                ticker.run()
                if (player.playbackState == Player.STATE_ENDED) {
                    // A reopen still pending (a seek just before the end) must not prepare the song again after
                    // "Finished"; it is dropped like one pending at an automatic transition, and the level comes back.
                    reopen.clear(); silence.completePause(); silence.end(SeekSilence.Exit.ENDED)
                    current()?.let { emit(EngineEvent.Ended(it.occurrence)) }
                } else emitState()
            }
            override fun onPlayerError(error: PlaybackException) {
                reopen.clear() // Nor after a failure.
                silence.completePause(); silence.end(SeekSilence.Exit.FAILED) // The player stopped and dropped its audio.
                current()?.let { emit(EngineEvent.Failed(it.occurrence, failure(error))) }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
                initializedTimestampMs: Long, initializationDurationMs: Long) {
                this@ExoAudioEngine.decoderName = decoderName
                PlaybackDiagnostics.onAudioDecoderInitialized(decoderName)
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                dev.coda.android.diag.BatteryLog.loadError(error)
                if (released || eventTime.timeline.isEmpty) return
                val item = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem
                val failed = sources[item.mediaId] ?: return
                // Exo retries the current stream itself; a transcode re-open downloads from byte 0 again. The source
                // is swapped for the complete cached copy only when the buffered audio is nearly spent: re-preparing drops
                // the buffer (and the preloaded successor), which must not be heard while there is still sound to play.
                // A later failed retry reports again, with less buffer left.
                if (failed.occurrence == current()?.occurrence && !wasCanceled && failed.source.transcodeFormat != null &&
                    player.totalBufferedDuration < SWAP_BELOW_BUFFER_MS)
                    emit(EngineEvent.StreamInterrupted(failed.occurrence))
                if (failed.occurrence != current()?.occurrence) {
                    emit(EngineEvent.Failed(failed.occurrence, failure(error)))
                    updateNext(null) // Discard a failed preparation while the current renderer keeps playing.
                }
            }
        })
        handler.post(ticker)
    }

    private fun current() = player.currentMediaItem?.let { sources[it.mediaId] }
    private fun emit(event: EngineEvent) { if (!released) listener?.onEvent(event) }
    private fun emitState() {
        current()?.let {
            // A faded pause counts as paused from its start; the audio (and the position) runs on until its end.
            emit(EngineEvent.State(it.occurrence, player.isPlaying && !silence.pausing, player.playbackState == Player.STATE_BUFFERING,
                player.duration.takeIf { duration -> duration != C.TIME_UNSET && duration > 0 }))
        }
    }

    /** Per media id, the stream's byte source (its `timeOffset`) and its song-time wrapper (local seeks). */
    private class Stream(val data: dev.coda.android.cache.TrackByteSource.Factory, val source: TranscodeOffsetSource)
    private val streamOffsets = mutableMapOf<String, Stream>()
    /** A seek inside an uncached transcode that needs a new stream waits here for a later one (one request per burst). */
    private val reopen = LatestReopen { delayMs, block ->
        val runnable = Runnable { if (!released) block() }
        handler.postDelayed(runnable, delayMs)
        ({ handler.removeCallbacks(runnable) })
    }
    /** The old audio is faded out while a reopen waits; see [SeekSilence] for when the level comes back. */
    private val silence = SeekSilence({ player.volume }, { volume -> player.volume = volume; volumeApplied?.invoke(volume) }) { delayMs, block ->
        val runnable = Runnable { if (!released) block() }
        handler.postDelayed(runnable, delayMs)
        ({ handler.removeCallbacks(runnable) })
    }
    /** Tests only (main thread): every output volume the engine sets. */
    @androidx.annotation.VisibleForTesting internal var volumeApplied: ((Float) -> Unit)? = null
    /** Asks the server once, off the main thread, whether it starts transcodes at `timeOffset`, so seeks can use it. */
    private val capabilityQuery = java.util.concurrent.atomic.AtomicBoolean()
    private fun learnTranscodeOffset() {
        // A known answer, yes or no, needs no thread.
        if (remoteStreams.knownTranscodeOffset() != null || !capabilityQuery.compareAndSet(false, true)) return
        Thread({ try { remoteStreams.supportsTranscodeOffset() } catch (_: Exception) {} finally { capabilityQuery.set(false) } },
            "transcode-offset").apply { isDaemon = true }.start()
    }
    override fun seeksInsideTranscodes(): Boolean = remoteStreams.knownTranscodeOffset() == true

    /** [startPositionMs]: where this item starts playing; an uncached transcode is asked to start there. */
    private fun source(item: EngineItem, startPositionMs: Long = 0): MediaSource {
        dev.coda.android.diag.BatteryLog.event(dev.coda.android.diag.BatteryIds.EV_ENGINE_SOURCE, if (item.source is TrackSource.CachedFile) 1 else 0)
        val id = item.occurrence.value.toString()
        val token = audio.beginSource(item.occurrence)
        sources[id] = item
        audio.gains[item.occurrence] = item.gainDb
        val uri = Uri.parse("coda://occurrence/$id")
        // One factory per occurrence: an accepted Opus → original fallback is reused by every later open.
        val data = dev.coda.android.cache.TrackByteSource.Factory(item.source, remoteStreams,
            TranscodeOffset.requestSeconds(item.source, startPositionMs)) { fallback ->
            handler.post {
                if (released) return@post
                audio.fromSource(item.occurrence, token) {
                    sources[id]?.let { sources[id] = it.copy(source = fallback) }
                    emit(EngineEvent.SourceFallback(item.occurrence, fallback))
                }
            }
        }
        val extractors = ExtractorsFactory {
            DefaultExtractorsFactory().createExtractors().map { extractor ->
                val patched = if (extractor is androidx.media3.extractor.ogg.OggExtractor)
                    dev.coda.android.playback.ogg.OggExtractor(
                        // An offset stream's last granule counts from the offset; the end cap counts song frames
                        // (Opus decodes at 48 kHz).
                        { frames -> audio.fromSource(item.occurrence, token) {
                            audio.endFrames[item.occurrence] = frames + data.startOffsetUs * 48 / 1_000 } },
                        { offset -> audio.fromSource(item.occurrence, token) { audio.seekFrameOffsets[item.occurrence] = offset } },
                    )
                else extractor
                val edits = if (extractor is androidx.media3.extractor.mp4.Mp4Extractor) Mp4EditScanner() else null
                val scanned = edits?.wrap(patched) ?: patched
                object : androidx.media3.extractor.Extractor by scanned {
                    override fun init(output: androidx.media3.extractor.ExtractorOutput) {
                        patched.init(object : androidx.media3.extractor.ExtractorOutput by output {
                            override fun track(id: Int, type: Int): androidx.media3.extractor.TrackOutput {
                                val track = output.track(id, type)
                                return object : androidx.media3.extractor.TrackOutput by track {
                                    override fun format(format: Format) {
                                        audio.fromSource(item.occurrence, token) {
                                        format.sampleMimeType?.let { audio.mimeTypes[item.occurrence] = it }
                                        if (format.sampleMimeType == MimeTypes.AUDIO_AAC) {
                                            edits?.edit()?.let { audio.mp4EditFrames[item.occurrence] = it }
                                            if (format.sampleRate > 0) audio.sourceSampleRates[item.occurrence] = format.sampleRate
                                        }
                                        if (format.sampleMimeType == MimeTypes.AUDIO_OPUS) {
                                            val header = format.initializationData.firstOrNull()
                                            if (header != null && header.size >= 12) {
                                                audio.opusPreSkipFrames[item.occurrence] =
                                                    (header[10].toInt() and 255) or ((header[11].toInt() and 255) shl 8)
                                            }
                                        }
                                        }
                                        track.format(format)
                                    }
                                }
                            }
                        })
                    }
                }
            }.toTypedArray()
        }
        val cacheKey = (item.source as? TrackSource.Remote)?.let { "song/${it.songId}/${it.format.name}" }
        val transcode = item.source.isUncachedTranscode()
        if (transcode) learnTranscodeOffset()
        // the pre-roll works in stream time, inside the offset (identity for a stream started at 0).
        return TranscodeOffsetSource(OpusPreRollSource(ProgressiveMediaSource.Factory(data, extractors).createMediaSource(MediaItem.Builder()
            .setMediaId(id).setUri(uri).setCustomCacheKey(cacheKey).setTag(item).build())), data::startOffsetUs, localSeeks = transcode)
            .also { streamOffsets[id] = Stream(data, it) }
    }

    private fun TrackSource.isUncachedTranscode() = this is TrackSource.Remote && format == StreamFormat.OPUS_128

    private val appContext = context.applicationContext

    override fun load(current: EngineItem, next: EngineItem?, startPositionMs: Long, playWhenReady: Boolean) {
        if (released) return
        reopen.clear()
        audio.retireSources(); sources.clear(); streamOffsets.clear(); audio.endFrames.clear(); audio.gains.clear(); audio.mimeTypes.clear(); audio.opusPreSkipFrames.clear(); audio.seekFrameOffsets.clear(); audio.encoderDelayFrames.clear(); audio.encoderPaddingFrames.clear(); audio.mp4EditFrames.clear(); audio.sourceSampleRates.clear()
        audio.inPlaceSeeks.clear()
        val sources = listOfNotNull(source(current, startPositionMs), next?.let { source(it) })
        audio.loadedFirst = sources.first().mediaItem
        player.setMediaSources(sources, 0, startPositionMs.coerceAtLeast(0))
        player.playWhenReady = playWhenReady
        player.prepare()
        silence.end(SeekSilence.Exit.LOADED) // After the new sources: the player drops the old audio first.
        sample(rebase = true)
        pendingStart.arm()
    }
    override fun updateNext(next: EngineItem?) {
        if (released || player.mediaItemCount == 0) return
        val index = player.currentMediaItemIndex
        if (index > 0) player.removeMediaItems(0, index)
        val existing = if (player.mediaItemCount > 1) sources[player.getMediaItemAt(1).mediaId] else null
        if (existing != next) {
            if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
            next?.let { player.addMediaSource(source(it)) }
        }
        val retained = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        sources.keys.retainAll(retained); streamOffsets.keys.retainAll(retained)
        val occurrences = sources.values.map { it.occurrence }.toSet()
        audio.retainSources(occurrences)
        audio.gains.keys.retainAll(occurrences); audio.endFrames.keys.retainAll(occurrences); audio.mimeTypes.keys.retainAll(occurrences); audio.opusPreSkipFrames.keys.retainAll(occurrences); audio.seekFrameOffsets.keys.retainAll(occurrences)
        audio.encoderDelayFrames.keys.retainAll(occurrences); audio.encoderPaddingFrames.keys.retainAll(occurrences); audio.mp4EditFrames.keys.retainAll(occurrences); audio.sourceSampleRates.keys.retainAll(occurrences)
        audio.inPlaceSeeks.retainAll(occurrences)
    }
    override fun setGain(gainDb: Double) { if (!released) current()?.let { audio.gains[it.occurrence] = gainDb } }
    override fun play() {
        if (released) return
        val wasPausing = silence.pausing
        if (reopen.pendingMs != null) silence.begin() // Resumed while the reopen still waits: the old audio stays silent.
        else if (!player.playWhenReady || wasPausing) silence.fadeIn() // MP-3; during a faded pause it cancels the pause.
        player.play()
        if (wasPausing) emitState() // The player never stopped, so it reports nothing itself.
    }
    /** While audio plays, a ~150 ms fade first ([SeekSilence.fadeOut]); otherwise at once. */
    override fun pause() {
        if (released) return
        if (player.isPlaying) silence.fadeOut { player.pause() } else pauseNow()
    }
    override fun pauseNow() {
        if (released) return
        silence.completeSeek() // A seek still fading out is not lost.
        player.pause()
        silence.end(SeekSilence.Exit.PAUSED) // The next play resumes at the normal level; a fade under way is dropped.
    }
    override fun replaceCurrentSource(source: TrackSource, positionMs: Long) {
        if (released) return
        silence.completePause() // The new source must not drop a faded pause under way.
        // A reopen (held silent) or a continue-from-cache seek while playing: the seek fade; the level comes back
        // when the new source plays. Otherwise at once, as before.
        if (fadesSeek()) silence.seek(positionMs) { replaceNow(source, positionMs) }
        else { replaceNow(source, positionMs); silence.end(SeekSilence.Exit.REOPENED) }
    }
    /** A seek now would be heard: audio plays, or a fade (resume, reopen silence, seek) is under way. */
    private fun fadesSeek() = player.playWhenReady && (player.isPlaying || silence.active)
    private fun replaceNow(source: TrackSource, positionMs: Long) {
        reopen.clear()
        val item = current() ?: return silence.end(SeekSilence.Exit.SUPERSEDED)
        val moved = item.copy(source = source, gainDb = audio.gains[item.occurrence] ?: item.gainDb)
        val next = if (player.mediaItemCount > 1) sources[player.getMediaItemAt(1).mediaId] else null
        audio.inPlaceSeeks.remove(item.occurrence)
        streamOffsets.clear()
        val sources = listOfNotNull(source(moved, positionMs), next?.let { source(it) })
        audio.loadedFirst = sources.first().mediaItem
        player.setMediaSources(sources, 0, positionMs.coerceAtLeast(0))
        player.prepare()
        sample(rebase = true)
        pendingStart.arm()
    }
    override fun seekTo(positionMs: Long) {
        if (released) return
        silence.completePause() // A seek during a faded pause pauses now (and so starts no seek silence).
        val item = current()
        val stream = player.currentMediaItem?.mediaId?.let(streamOffsets::get)
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0)
        if (item != null && stream != null && item.source.isUncachedTranscode()) {
            // Forward into what is buffered: the player seeks, TranscodeOffsetSource serves it from the queue.
            // Otherwise a stream started at `timeOffset` holds nothing before it, and one that may still start
            // there (its first open is pending, Rex R2-P3-1) is no better: open the item again at the target, as for any
            // seek past the buffer when the server supports `timeOffset`. Reopens are debounced (one request per drag).
            val target = positionMs.coerceAtLeast(0).let { if (duration != null) it.coerceAtMost(duration) else it }
            val offsetStream = stream.data.startOffsetUs > 0 || stream.data.mayStartAtOffset
            when (val plan = TranscodeSeek.plan(target, duration, stream.source.inBuffer(target * 1_000), offsetStream,
                remoteStreams.knownTranscodeOffset() == true)) {
                is TranscodeSeek.Plan.Reopen -> {
                    val occurrence = item.occurrence
                    if (player.playWhenReady) silence.begin() // The old audio must not play on meanwhile.
                    reopen.request(plan.targetMs) { at ->
                        current()?.takeIf { it.occurrence == occurrence }?.let { replaceCurrentSource(it.source, at) }
                            ?: silence.end(SeekSilence.Exit.SUPERSEDED)
                    }
                    return
                }
                TranscodeSeek.Plan.Local, TranscodeSeek.Plan.Player -> Unit
            }
        }
        val target = if (duration != null) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        // While audio plays, the seek runs once faded out (a burst keeps only the last), and fades in on play.
        if (fadesSeek()) silence.seek(target) { if (current()?.occurrence == item?.occurrence) seekNow(item, target) }
        else { seekNow(item, target); silence.end(SeekSilence.Exit.SUPERSEDED) } // After the seek, which flushes the audio.
    }
    private fun seekNow(item: EngineItem?, target: Long) {
        reopen.clear()
        item?.let { audio.inPlaceSeeks.add(it.occurrence) }
        player.seekTo(target)
        sample(rebase = true)
        pendingStart.arm()
    }
    override fun stop() {
        if (released) return
        reopen.clear()
        player.stop(); player.clearMediaItems()
        silence.end(SeekSilence.Exit.STOPPED)
        sample(rebase = true)
        pendingStart.clear()
        audio.retireSources(); sources.clear(); streamOffsets.clear(); audio.endFrames.clear(); audio.gains.clear(); audio.mimeTypes.clear(); audio.opusPreSkipFrames.clear(); audio.seekFrameOffsets.clear(); audio.encoderDelayFrames.clear(); audio.encoderPaddingFrames.clear(); audio.mp4EditFrames.clear(); audio.sourceSampleRates.clear()
        audio.inPlaceSeeks.clear()
    }
    /** A debounced reopen shows its target at once. */
    override fun positionMs(): Long = reopen.pendingMs ?: silence.pendingSeekMs ?: tracker.positionMs(SystemClock.elapsedRealtime())
    override fun details(): EngineDetails? {
        if (released) return null
        val item = current() ?: return null
        val format = player.audioFormat
        val occurrence = item.occurrence
        return EngineDetails(
            containerMime = format?.containerMimeType, codecMime = format?.sampleMimeType ?: audio.mimeTypes[occurrence],
            bitrate = format?.bitrate?.takeIf { it > 0 }, sampleRate = format?.sampleRate?.takeIf { it > 0 },
            channels = format?.channelCount?.takeIf { it > 0 },
            pcmEncodingBits = when (format?.pcmEncoding) {
                C.ENCODING_PCM_8BIT -> 8; C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32; else -> null
            },
            decoderName = decoderName,
            encoderDelayFrames = audio.encoderDelayFrames[occurrence] ?: format?.encoderDelay ?: 0,
            encoderPaddingFrames = format?.encoderPadding ?: 0,
            opusPreSkipFrames = audio.opusPreSkipFrames[occurrence], trimEndFrames = audio.endFrames[occurrence],
            gaplessNextPrepared = player.mediaItemCount > 1,
            bufferedAheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
        )
    }
    override fun release() {
        if (released) return
        released = true; listener = null; handler.removeCallbacks(ticker); reopen.clear(); silence.release()
        player.release(); audio.retireSources(); sources.clear(); streamOffsets.clear(); audio.endFrames.clear(); audio.gains.clear(); audio.mimeTypes.clear(); audio.opusPreSkipFrames.clear(); audio.seekFrameOffsets.clear(); audio.encoderDelayFrames.clear(); audio.encoderPaddingFrames.clear(); audio.mp4EditFrames.clear(); audio.sourceSampleRates.clear()
        audio.inPlaceSeeks.clear()
    }

    private fun failure(error: Throwable): PlaybackFailure = playbackFailure(error)
}
