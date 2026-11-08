package dev.coda.core.testing

/** Generated, non-personal fixtures; see tools/synthetic-library/generate.py. */
object SyntheticLibrary {
    data class Track(val id: String, val title: String, val sourceFrames: Int) {
        val duration: Double get() = sourceFrames / 44100.0
        fun bytes(format: String = "raw"): ByteArray = resource(
            "$id.${if (format == "opus") "opus" else "flac"}",
        )
    }

    data class Album(val id: String, val name: String, val tracks: List<Track>) {
        val artist = "Synthetic Artist"
        fun coverBytes(): ByteArray = resource("cover-${id.removePrefix("album-")}.jpg")
    }

    val albums = listOf(
        Album("album-1", "Continuous Signal", listOf(
            Track("track-1", "Signal One", 91877),
            Track("track-2", "Signal Two", 88211),
        )),
        // A distinct album/song ID reuses the first generated signal to keep binaries small.
        Album("album-2", "Colour Study", listOf(Track("track-3", "Colour One", 91877))),
    )

    private fun resource(name: String): ByteArray = checkNotNull(
        SyntheticLibrary::class.java.getResourceAsStream(
            "/synthetic-library/${name.replace("track-3", "track-1")}",
        ),
    ) { "Missing synthetic fixture" }.use { it.readBytes() }
}
