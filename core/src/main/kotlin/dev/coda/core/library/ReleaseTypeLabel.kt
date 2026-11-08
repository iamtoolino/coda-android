package dev.coda.core.library

/**
 * The display label of an album's release type. Servers send the type in any case ("album", "EP", "Single"); every place that
 * shows one uses this, so the case is the same everywhere. Known types get their usual spelling, an unknown one gets a capital first
 * letter and keeps the rest; no type at all is "Album".
 */
object ReleaseTypeLabel {
    private val known = mapOf(
        "album" to "Album", "ep" to "EP", "single" to "Single", "compilation" to "Compilation", "live" to "Live",
        "soundtrack" to "Soundtrack", "remix" to "Remix", "mixtape" to "Mixtape", "demo" to "Demo", "broadcast" to "Broadcast",
        "audiobook" to "Audiobook", "spokenword" to "Spoken word", "spoken word" to "Spoken word", "interview" to "Interview",
        "djmix" to "DJ mix", "dj-mix" to "DJ mix", "dj mix" to "DJ mix", "other" to "Other",
    )

    /** The label of one type string; null for a blank one. */
    fun label(type: String?): String? {
        val trimmed = type?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return known[trimmed.lowercase()] ?: trimmed.replaceFirstChar { it.uppercaseChar() }
    }

    /** The label of an album's first release type, "Album" when it has none. */
    fun of(types: List<String>?): String = types?.firstNotNullOfOrNull { label(it) } ?: "Album"
}
