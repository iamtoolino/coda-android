package io.github.iamtoolino.coda.ui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.data.AlbumListType
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual, emulator-only diagnostic. Run through scripts/check-artwork-loading.sh. */
@RunWith(AndroidJUnit4::class)
class ArtworkLoadingDiagnosticTest {
    @Test
    fun loadRandomHeroArtwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Manual artwork loading diagnostic was not requested",
            InstrumentationRegistry.getArguments().getString(ARGUMENT_NAME) == "true",
        )

        val context = instrumentation.targetContext
        AppGraph.initialize(context)
        check(AppGraph.sessionSnapshot() != null) {
            "Coda is not connected to a server on this emulator"
        }

        val samples = AppGraph.withCurrentSession {
            albums(AlbumListType.ALPHABETICAL, ALBUM_POOL_SIZE)
                .shuffled(Random(SAMPLE_SEED))
                .take(SAMPLE_SIZE)
                .mapNotNull { album ->
                    val url = coverArtUrl(album.artworkId, HERO_SIZE) ?: return@mapNotNull null
                    ArtworkSample(album.id, album.name, url)
                }
        }
        check(samples.isNotEmpty()) { "The connected server returned no album artwork" }

        val rows = samples.map { sample ->
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withTimeout(REQUEST_DEADLINE_MILLIS) {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(sample.url)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .diskCachePolicy(CachePolicy.DISABLED)
                            .build(),
                    )
                }
            }
            ArtworkResult(
                id = sample.id,
                name = sample.name,
                elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
                success = result.getOrNull() is SuccessResult,
                error = result.exceptionOrNull()?.javaClass?.simpleName
                    ?: (result.getOrNull() as? ErrorResult)?.throwable?.javaClass?.simpleName
                    ?: result.getOrNull()?.let { it::class.java.simpleName },
            )
        }

        val directory = File(context.cacheDir, REPORT_DIRECTORY).apply { mkdirs() }
        File(directory, REPORT_FILE).writeText(renderReport(rows), Charsets.UTF_8)
        val failures = rows.count { !it.success }
        check(failures == 0) {
            "$failures of ${rows.size} uncached 1200 px artwork requests failed"
        }
    }

    private fun renderReport(rows: List<ArtworkResult>): String = buildString {
        appendLine("result\telapsed_ms\talbum_id\talbum")
        rows.forEach { row ->
            append(if (row.success) "success" else "failure")
            append('\t').append(row.elapsedMillis)
            append('\t').append(row.id.tsvSafe())
            append('\t').append(row.name.tsvSafe())
            row.error?.let { append(" (").append(it.tsvSafe()).append(')') }
            appendLine()
        }
    }

    private fun String.tsvSafe(): String = replace('\t', ' ').replace('\n', ' ')

    private data class ArtworkSample(val id: String, val name: String, val url: String)

    private data class ArtworkResult(
        val id: String,
        val name: String,
        val elapsedMillis: Long,
        val success: Boolean,
        val error: String?,
    )

    private companion object {
        const val ARGUMENT_NAME = "codaArtworkLoading"
        const val SAMPLE_SIZE = 100
        const val ALBUM_POOL_SIZE = 500
        const val SAMPLE_SEED = 0xC0DA
        const val HERO_SIZE = 1_200
        const val REQUEST_DEADLINE_MILLIS = 45_000L
        const val REPORT_DIRECTORY = "artwork-loading"
        const val REPORT_FILE = "report.tsv"
    }
}
