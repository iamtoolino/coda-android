package dev.coda.android.session

/** Release builds use no persistent debug preference. */
internal interface AutoLoginPreference {
    suspend fun disabled(): Boolean
    suspend fun setDisabled(value: Boolean)
}

internal object NoAutoLoginPreference : AutoLoginPreference {
    override suspend fun disabled() = false
    override suspend fun setDisabled(value: Boolean) = Unit
}
