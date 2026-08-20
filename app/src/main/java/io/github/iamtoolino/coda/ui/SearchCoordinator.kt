package io.github.iamtoolino.coda.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.iamtoolino.coda.data.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class SearchState(
    val query: String = "",
    val result: SearchResult? = null,
    val loadedQuery: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

@Stable
internal class SearchCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 350L,
    private val delayBlock: suspend (Long) -> Unit = { delay(it) },
    private val loader: suspend (String) -> SearchResult,
) {
    var state by mutableStateOf(SearchState())
        private set

    private var searchJob: Job? = null
    private var generation = 0L

    fun updateQuery(query: String): Job? {
        if (query == state.query && (searchJob?.isActive == true || state.loadedQuery == query)) {
            return searchJob
        }
        val retainedResult = state.result.takeIf { state.loadedQuery == query }
        val retainedLoadedQuery = state.loadedQuery.takeIf { it == query }
        if (query.length < MINIMUM_QUERY_LENGTH) {
            generation++
            searchJob?.cancel()
            searchJob = null
            state = SearchState(query = query, loadedQuery = query)
            return null
        }
        return startSearch(
            query = query,
            result = retainedResult,
            loadedQuery = retainedLoadedQuery,
            debounce = true,
        )
    }

    fun refresh(): Job? {
        val query = state.query
        if (query.length < MINIMUM_QUERY_LENGTH) return null
        return startSearch(
            query = query,
            result = state.result.takeIf { state.loadedQuery == query },
            loadedQuery = state.loadedQuery.takeIf { it == query },
            debounce = false,
        )
    }

    private fun startSearch(
        query: String,
        result: SearchResult?,
        loadedQuery: String?,
        debounce: Boolean,
    ): Job {
        val requestGeneration = ++generation
        searchJob?.cancel()
        state = SearchState(
            query = query,
            result = result,
            loadedQuery = loadedQuery,
            isLoading = !debounce,
        )
        val job = scope.launch {
            try {
                if (debounce) {
                    delayBlock(debounceMillis)
                    if (generation != requestGeneration) return@launch
                    state = state.copy(isLoading = true)
                }
                val loadedResult = loader(query)
                if (generation == requestGeneration) {
                    state = SearchState(
                        query = query,
                        result = loadedResult,
                        loadedQuery = query,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == requestGeneration) {
                    state = SearchState(
                        query = query,
                        result = result,
                        loadedQuery = loadedQuery,
                        errorMessage = error.message ?: "Network request failed",
                    )
                }
            }
        }
        searchJob = job
        return job
    }

    private companion object {
        const val MINIMUM_QUERY_LENGTH = 2
    }
}
