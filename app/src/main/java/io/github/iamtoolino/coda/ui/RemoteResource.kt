package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class RemoteResourceState<K, V>(
    val key: K,
    val value: V? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)

@Stable
internal class RemoteResourceCoordinator<K, V>(
    private val scope: CoroutineScope,
    initialKey: K,
    private val loader: suspend (K) -> V,
) {
    var state by mutableStateOf(RemoteResourceState<K, V>(key = initialKey))
        private set

    private var loadJob: Job? = null
    private var generation = 0L

    fun load(key: K = state.key): Job {
        val requestGeneration = ++generation
        loadJob?.cancel()
        val retainedValue = state.value.takeIf { state.key == key }
        state = RemoteResourceState(
            key = key,
            value = retainedValue,
            isLoading = true,
        )
        val job = scope.launch {
            try {
                val value = loader(key)
                if (generation == requestGeneration) {
                    state = RemoteResourceState(key = key, value = value, isLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == requestGeneration) {
                    state = RemoteResourceState(
                        key = key,
                        value = retainedValue,
                        isLoading = false,
                        errorMessage = error.message ?: "Network request failed",
                    )
                }
            }
        }
        loadJob = job
        return job
    }

    fun refresh(): Job = load(state.key)
}

internal typealias RemoteCollectionCoordinator<K, T> = RemoteResourceCoordinator<K, List<T>>
internal typealias RemoteDetailCoordinator<K, T> = RemoteResourceCoordinator<K, T>
