package dev.coda.core.playback

/** Deterministic two-item engine. Tests explicitly deliver events, including stale IDs. */
class FakeAudioEngine(private val queuedDelivery: Boolean = false) : AudioEngine {
    private val pendingEvents = ArrayDeque<EngineEvent>()
    data class LoadCall(val current: EngineItem, val next: EngineItem?, val startPositionMs: Long, val playWhenReady: Boolean)
    override val name = "Fake"
    override var listener: AudioEngine.Listener? = null
    val loadCalls = mutableListOf<LoadCall>()
    val updatedNextItems = mutableListOf<EngineItem?>()
    val gains = mutableListOf<Double>()
    val seeks = mutableListOf<Long>()
    var current: EngineItem? = null
        private set
    var next: EngineItem? = null
        private set
    var position = 0L
    var transcodeSeeks = false
    override fun seeksInsideTranscodes() = transcodeSeeks
    var playCalls = 0
        private set
    var pauseCalls = 0
        private set
    var stopCalls = 0
        private set
    var released = false
        private set

    override fun load(current: EngineItem, next: EngineItem?, startPositionMs: Long, playWhenReady: Boolean) {
        this.current = current
        this.next = next
        position = startPositionMs
        loadCalls += LoadCall(current, next, startPositionMs, playWhenReady)
    }
    override fun updateNext(next: EngineItem?) {
        this.next = next
        updatedNextItems += next
    }
    override fun setGain(gainDb: Double) {
        current = current?.copy(gainDb = gainDb)
        gains += gainDb
    }
    override fun play() { playCalls++ }
    override fun pause() { pauseCalls++ }
    var pauseNowCalls = 0
        private set
    override fun pauseNow() { pauseNowCalls++ }
    override fun seekTo(positionMs: Long) { position = positionMs; seeks += positionMs }
    val replacedSources = mutableListOf<Pair<TrackSource, Long>>()
    override fun replaceCurrentSource(source: TrackSource, positionMs: Long) {
        current = current?.copy(source = source); position = positionMs; replacedSources += source to positionMs
    }
    override fun stop() { stopCalls++; current = null; next = null; position = 0 }
    override fun positionMs() = position
    override fun release() { released = true }

    fun emit(event: EngineEvent) {
        when (event) {
            is EngineEvent.Advanced -> if (next?.occurrence == event.to) {
                current = next; next = null; position = 0
            }
            is EngineEvent.Ended -> if (current?.occurrence == event.last) {
                current = null; next = null; position = 0
            }
            else -> Unit
        }
        if (queuedDelivery) pendingEvents.addLast(event) else listener?.onEvent(event)
    }
    /** Engine changes happen at emit time; only listener delivery waits for the test to drain. */
    fun deliverEvents() {
        while (pendingEvents.isNotEmpty()) {
            val event = pendingEvents.removeFirst()
            listener?.onEvent(event)
        }
    }
    fun advance() = emit(EngineEvent.Advanced(checkNotNull(next).occurrence))
    fun finish() = emit(EngineEvent.Ended(checkNotNull(current).occurrence))
    fun failCurrent(failure: PlaybackFailure) = emit(EngineEvent.Failed(checkNotNull(current).occurrence, failure))
}
