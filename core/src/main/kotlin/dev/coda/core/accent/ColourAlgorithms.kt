package dev.coda.core.accent

/**
 * Versions of the colour derivations whose results `library.db` keeps (OF-1 rows 4 and 5). Bump one whenever its result
 * for the same picture can change; stored colours of another version are dropped and derived again.
 */
object ColourAlgorithms {
    /** Album accent: ReducedJpegDecoder at 472 px, CgBoxDownsampler, AccentSampler, AccentSelector. */
    const val ACCENT = 1
    /** Artist photo: DS-11 Photo-matched page colour and AR-3 years from the 1440 px photo (ArtistBackdrop). */
    const val ARTIST_PHOTO = 1
}
