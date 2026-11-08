package dev.coda.android.session

internal suspend fun autoLogin(repository: SessionRepository) = Unit

internal fun autoLoginPreference(context: android.content.Context): AutoLoginPreference = NoAutoLoginPreference
