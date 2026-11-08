package dev.coda.core.cache

import dev.coda.core.cache.CacheFormatPolicy.Play
import dev.coda.core.connectivity.NetworkState
import dev.coda.core.playback.StreamFormat.OPUS_128
import dev.coda.core.playback.StreamFormat.RAW
import org.junit.Assert.*
import org.junit.Test

class CacheRulesTest {
    private val wifi = NetworkState(available = true, unmetered = true, cellular = false)
    private val mobile = NetworkState(available = true, unmetered = false, cellular = true)
    private val hotspot = NetworkState(available = true, unmetered = false, cellular = false)
    private val offline = NetworkState(available = false, unmetered = false, cellular = false)
    private val flac = SongFormat("flac", 900, 240)
    private val mp3at320 = SongFormat("mp3", 320, 240)
    private val mp3at128 = SongFormat("mp3", 128, 240)
    private val unknown = SongFormat()

    @Test fun numbersAreTheSpecifiedOnes() {
        assertEquals(365 * 86_400_000L, CacheRules.WAVEFORM_AGE_LIMIT_MS)
        assertEquals(20_000, CacheRules.WAVEFORM_CAP)
    }

    private val aac256 = SongFormat("m4a", 256, 240)
    /** Low-bitrate lossless: the bitrate alone must not give it the lossy exemption. */
    private val flac256 = SongFormat("flac", 256, 240)
    private val networks = listOf("Wi-Fi" to wifi, "mobile" to mobile, "hotspot" to hotspot)

    /** The PO 2026-10-26 rule, network only: lossy ≤ 320 kbit/s is always the original; the rest only on Wi-Fi. */
    private fun expected(network: NetworkState, song: SongFormat) =
        if (song.isLossyUpTo320 || network == wifi) RAW else OPUS_128

    @Test fun fullCaseTableForWhatIsFetchedWhenNothingIsCached() {
        // {Wi-Fi, mobile, hotspot} × {MP3 128, MP3 320, AAC 256, FLAC, unknown} × {play / read-ahead, offline add}.
        val songs = listOf("MP3 128" to mp3at128, "MP3 320" to mp3at320, "AAC 256" to aac256, "FLAC" to flac, "unknown" to unknown,
            "FLAC 256" to flac256)
        var cases = 0
        for ((networkName, network) in networks) for ((songName, song) in songs) {
            val label = "$networkName / $songName"
            val want = expected(network, song)
            // Play and read-ahead: the stream and what the read-ahead keeps.
            assertEquals("fetch $label", want, CacheFormatPolicy.fetchFormat(network, song))
            assertEquals("play $label", Play.Stream(want), CacheFormatPolicy.play(null, network, true, song))
            assertEquals("play, server unreachable $label", Play.Stream(want), CacheFormatPolicy.play(null, network, false, song))
            // Offline add: the same format, but it downloads on proper Wi-Fi only and waits elsewhere.
            assertEquals("add $label", want, CacheFormatPolicy.download(null, network, song))
            assertEquals("adds run $label", network == wifi, CacheFormatPolicy.addsDownload(network))
            // The add is planned for the Wi-Fi download, whatever network it is started on.
            assertEquals("add plan $label", expected(wifi, song), CacheFormatPolicy.download(null, CacheFormatPolicy.ADD_NETWORK, song))
            cases++
        }
        assertEquals(18, cases)
    }

    @Test fun lossyOriginalsUpTo320AreOriginalsOnEveryNetwork() {
        for (song in listOf(mp3at128, mp3at320, aac256, SongFormat("ogg", 192), SongFormat("opus", 96), SongFormat("mp3", 1)))
            for (network in listOf(wifi, mobile, hotspot, offline))
                assertEquals("$network $song", RAW, CacheFormatPolicy.fetchFormat(network, song))
        // Low-bitrate lossless files are not lossy originals: the network decides.
        for (song in listOf(flac256, SongFormat("FLAC", 100), SongFormat("wav", 300), SongFormat("aiff", 320), SongFormat("alac", 200),
            SongFormat("ape", 250), SongFormat("wv", 300), SongFormat("dsf", 320), SongFormat("tta", 300))) {
            assertTrue(song.toString(), song.isLossless && song.atMost320 && !song.isLossyUpTo320)
            assertEquals(RAW, CacheFormatPolicy.fetchFormat(wifi, song))
            for (network in listOf(mobile, hotspot, offline)) assertEquals(OPUS_128, CacheFormatPolicy.fetchFormat(network, song))
        }
        // Lossy or not clearly lossless suffixes (and no suffix) keep the exemption.
        for (suffix in listOf("mp3", "m4a", "aac", "ogg", "opus", "wma", "mp4", null)) assertFalse("$suffix", SongFormat(suffix, 200).isLossless)
        // One kbit/s above the cut, lossless files and unknown or zero bitrates depend on the network.
        for (song in listOf(SongFormat("mp3", 321), flac, SongFormat("flac", 0), SongFormat("flac", -1), unknown)) {
            assertEquals(RAW, CacheFormatPolicy.fetchFormat(wifi, song))
            for (network in listOf(mobile, hotspot, offline)) assertEquals(OPUS_128, CacheFormatPolicy.fetchFormat(network, song))
        }
    }

    @Test fun addsDownloadOnlyOnProperWifiAndAHotspotIsNotWifi() {
        // Proper Wi-Fi is unmetered and not cellular. A hotspot (metered Wi-Fi, not cellular), mobile
        // data (also when the carrier marks it unmetered) and no network are all "not Wi-Fi".
        assertTrue(CacheFormatPolicy.addsDownload(wifi))
        assertFalse(CacheFormatPolicy.addsDownload(hotspot))
        assertFalse(CacheFormatPolicy.addsDownload(mobile))
        assertFalse(CacheFormatPolicy.addsDownload(NetworkState(available = true, unmetered = true, cellular = true)))
        assertFalse(CacheFormatPolicy.addsDownload(offline))
        assertFalse(CacheFormatPolicy.addsDownload(NetworkState(available = false, unmetered = true, cellular = false)))
        assertTrue(CacheFormatPolicy.addsDownload(CacheFormatPolicy.ADD_NETWORK))
    }

    @Test fun aCachedCopyIsNeverDownloadedAgainOrDowngradedOnlyOpusIsReplacedInLosslessWifi() {
        for (network in listOf(wifi, mobile, hotspot)) for (song in listOf(flac, mp3at320, mp3at128, unknown)) {
            assertNull("Original re-fetched: $network $song", CacheFormatPolicy.download(RAW, network, song))
            val opus = CacheFormatPolicy.download(OPUS_128, network, song)
            if (network == wifi) assertEquals(RAW, opus) else assertNull(opus)
            assertEquals(CacheFormatPolicy.fetchFormat(network, song), CacheFormatPolicy.download(null, network, song))
        }
    }

    @Test fun alreadyCachedOriginalAndOpusCopiesFollowTheSameRulesForEverySong() {
        for ((networkName, network) in networks) for (song in listOf(mp3at128, mp3at320, aac256, flac, flac256, unknown)) {
            val label = "$networkName / $song"
            // A cached original is kept and plays first, never fetched again.
            assertNull(label, CacheFormatPolicy.download(RAW, network, song))
            assertEquals(label, Play.Cached(RAW), CacheFormatPolicy.play(RAW, network, true, song))
            // A cached Opus copy is only ever replaced by the original on Wi-Fi (even for a lossy original).
            val replaced = network == wifi
            assertEquals(label, if (replaced) RAW else null, CacheFormatPolicy.download(OPUS_128, network, song))
            assertEquals(label, if (replaced) Play.Stream(RAW) else Play.Cached(OPUS_128), CacheFormatPolicy.play(OPUS_128, network, true, song))
            assertEquals(label, Play.Cached(OPUS_128), CacheFormatPolicy.play(OPUS_128, network, false, song))
        }
    }

    @Test fun whatPlays() {
        for (network in listOf(wifi, mobile, hotspot, offline)) for (reachable in listOf(true, false)) {
            // A complete original plays first on any network.
            assertEquals(Play.Cached(RAW), CacheFormatPolicy.play(RAW, network, reachable, flac))
            val opus = CacheFormatPolicy.play(OPUS_128, network, reachable, flac)
            if (network == wifi && reachable) assertEquals(Play.Stream(RAW), opus)
            else assertEquals("Opus copy must play: $network $reachable", Play.Cached(OPUS_128), opus)
        }
        assertEquals(Play.Stream(RAW), CacheFormatPolicy.play(null, wifi, true, flac))
        assertEquals(Play.Stream(OPUS_128), CacheFormatPolicy.play(null, mobile, true, flac))
        assertEquals(Play.Stream(RAW), CacheFormatPolicy.play(null, mobile, true, mp3at320))
        // A FLAC on Wi-Fi is never Opus 128, however full the phone (no space mode left).
        assertEquals(Play.Stream(RAW), CacheFormatPolicy.play(null, wifi, true, SongFormat("flac", 256, 240)))
    }

    @Test fun estimatesUseOpusRateOrTheOriginalsSize() {
        assertEquals((240 * 16_000 * 1.02).toLong(), flac.estimate(OPUS_128))
        assertEquals((240 * 16_000 * 1.02).toLong(), unknown.estimate(OPUS_128)) // unknown duration: four minutes
        assertEquals(240L * 900 * 125, flac.estimate(RAW))
        assertEquals(31_000_000L, flac.copy(sizeBytes = 31_000_000).estimate(RAW))
        assertEquals(240L * 1411 * 125, unknown.estimate(RAW))
    }

    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { part ->
        when (part) { is String -> part.toByteArray().toList(); is Int -> listOf(part.toByte()); else -> error("part") }
    }.toByteArray()
    private val oggOpus = bytes("OggS", 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 19, "OpusHead", 1, 2)
    private val flacHead = bytes("fLaC", 0, 0, 0, 34)

    @Test fun arrivalIsDetectedFromContentTypeAndMagicBytes() {
        val request = OPUS_128
        assertEquals(OPUS_128, ArrivalDetector.kind(request, "audio/ogg", oggOpus))
        assertEquals(OPUS_128, ArrivalDetector.kind(request, "audio/ogg; charset=binary", ByteArray(0)))
        // Navidrome's pass-through and fallback send the original with its own type: that is an original.
        assertEquals(RAW, ArrivalDetector.kind(request, "audio/flac", flacHead))
        assertEquals(RAW, ArrivalDetector.kind(request, "audio/mpeg", bytes("ID3", 4, 0)))
        assertEquals(RAW, ArrivalDetector.kind(request, "audio/mpeg", bytes(0xFF, 0xFB, 0x90, 0x00)))
        // Magic bytes win over a misleading type; without a usable type they decide alone.
        assertEquals(RAW, ArrivalDetector.kind(request, "audio/ogg", flacHead))
        assertEquals(OPUS_128, ArrivalDetector.kind(request, "application/octet-stream", oggOpus))
        assertEquals(OPUS_128, ArrivalDetector.kind(request, null, oggOpus))
        assertEquals(RAW, ArrivalDetector.kind(request, null, flacHead))
        assertEquals(OPUS_128, ArrivalDetector.kind(request, null, ByteArray(8)))
        assertEquals(RAW, ArrivalDetector.kind(request, "audio/mp4", ByteArray(8)))
        assertTrue(ArrivalDetector.isOggOpus(oggOpus)); assertFalse(ArrivalDetector.isOggOpus(flacHead))
        // An Opus original at ≤ 128 kbit/s passes through untouched for an Opus 128 request.
        assertEquals(RAW, ArrivalDetector.kind(request, "audio/ogg", oggOpus, SongFormat("opus", 96)))
        assertEquals(OPUS_128, ArrivalDetector.kind(request, "audio/ogg", oggOpus, SongFormat("opus", 160)))
        // An original request always stores an original.
        assertEquals(RAW, ArrivalDetector.kind(RAW, "audio/ogg", oggOpus))
    }
}
