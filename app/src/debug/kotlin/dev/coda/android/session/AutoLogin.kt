package dev.coda.android.session

import dev.coda.android.BuildConfig

/** Values are supplied only to the debug variant, from the untracked developer properties. */
internal suspend fun autoLogin(repository: SessionRepository) {
    if (repository.state.value == SessionState.SignedOut && BuildConfig.QA_SERVERURL.isNotBlank()) {
        repository.loginAutomatically(BuildConfig.QA_SERVERURL, BuildConfig.QA_USERNAME, BuildConfig.QA_PASSWORD)
    }
}

internal fun autoLoginPreference(context: android.content.Context): AutoLoginPreference =
    object : AutoLoginPreference {
        private val file by lazy { java.io.File(context.noBackupFilesDir, "debug-auto-login-disabled") }
        override suspend fun disabled(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            file.exists()
        }
        override suspend fun setDisabled(value: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (value) file.writeText("disabled")
            else check(!file.exists() || file.delete()) { "Couldn't update debug login preference" }
        }
    }
