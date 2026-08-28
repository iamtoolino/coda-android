package io.github.iamtoolino.coda.ui.theme

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import androidx.palette.graphics.Palette
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.color.utilities.QuantizerCelebi
import com.google.android.material.color.utilities.Score
import io.github.iamtoolino.coda.AppGraph
import io.github.iamtoolino.coda.data.Album
import io.github.iamtoolino.coda.data.AlbumListType
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Manual, emulator-only diagnostic. Run through scripts/compare-artwork-colors.sh. */
@RunWith(AndroidJUnit4::class)
class ArtworkColorComparisonTest {
    @Test
    fun generateComparisonReport() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Manual artwork comparison was not requested",
            InstrumentationRegistry.getArguments().getString(ARGUMENT_NAME) == "true",
        )

        val context = instrumentation.targetContext
        AppGraph.initialize(context)
        check(AppGraph.sessionSnapshot() != null) {
            "Coda is not connected to a server on this emulator"
        }

        val albums = AppGraph.withCurrentSession {
            (search(REFERENCE_QUERY).album + albums(AlbumListType.ALPHABETICAL, REPORT_SIZE))
                .distinctBy(Album::id)
                .take(REPORT_SIZE)
        }
        check(albums.isNotEmpty()) { "The connected server returned no albums" }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val rows = withContext(Dispatchers.IO) {
            albums.map { album ->
                runCatching { compareAlbum(client, album) }
                    .getOrElse { error -> ComparisonRow.failed(album, error) }
            }
        }

        val directory = File(context.cacheDir, REPORT_DIRECTORY).apply { mkdirs() }
        File(directory, REPORT_FILE).writeText(renderReport(rows), Charsets.UTF_8)
    }

    private suspend fun compareAlbum(client: OkHttpClient, album: Album): ComparisonRow {
        val bytes = AppGraph.withCurrentSession {
            val url = coverArtUrl(album.id, COVER_SIZE)
                ?: error("No canonical album artwork")
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                check(response.isSuccessful) { "Artwork request failed (${response.code})" }
                response.body.bytes()
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("Artwork could not be decoded")
        return try {
            val palette = Palette.from(bitmap).maximumColorCount(PALETTE_COLOR_COUNT).generate()
            val palettePopulation = palette.swatches.sumOf(Palette.Swatch::getPopulation)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val materialSeed = Score.score(
                QuantizerCelebi.quantize(pixels, MATERIAL_COLOR_COUNT),
            ).firstOrNull()
            val android = WallpaperColors.fromBitmap(bitmap)
            ComparisonRow(
                album = album,
                imageData = bitmap.jpegDataUrl(),
                macV2 = CodaAccentExtractor.extractOrFallback(bitmap).toArgb(),
                paletteDominant = palette.dominantSwatch.toCandidate(palettePopulation),
                paletteVibrant = palette.vibrantSwatch.toCandidate(palettePopulation),
                paletteMuted = palette.mutedSwatch.toCandidate(palettePopulation),
                materialSeed = materialSeed,
                androidPrimary = android.primaryColor.toArgb(),
                androidSecondary = android.secondaryColor?.toArgb(),
                androidTertiary = android.tertiaryColor?.toArgb(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderReport(rows: List<ComparisonRow>): String = buildString {
        append(
            """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Coda artwork color comparison</title>
              <style>
                :root { color-scheme: dark; font-family: system-ui, sans-serif; }
                * { box-sizing: border-box; }
                body { margin: 0; background: #070809; color: #eee; }
                header { position: sticky; top: 0; z-index: 1; padding: 22px 28px;
                  background: rgba(7,8,9,.94); border-bottom: 1px solid #292c30; backdrop-filter: blur(16px); }
                h1 { margin: 0 0 6px; font-size: 24px; }
                header p { margin: 0; color: #aeb3ba; }
                main { display: grid; grid-template-columns: repeat(auto-fill,minmax(320px,1fr)); gap: 18px;
                  padding: 24px; }
                article { overflow: hidden; border: 1px solid #292c30; border-radius: 16px; background: #111315; }
                .cover { width: 100%; aspect-ratio: 1; display: block; object-fit: cover; background: #090a0b; }
                .missing { display: grid; place-items: center; color: #8f969e; padding: 28px; }
                .meta { padding: 15px 16px 12px; }
                h2 { margin: 0 0 4px; font-size: 18px; line-height: 1.25; }
                .artist,.error { margin: 0; color: #9da3aa; font-size: 14px; }
                .error { color: #e49a9a; }
                .colors { display: grid; grid-template-columns: repeat(4,1fr); min-height: 208px; }
                .swatch { padding: 11px 9px; display: flex; flex-direction: column; justify-content: flex-end;
                  text-shadow: 0 1px 3px #000,0 0 6px #000; }
                .swatch b { font-size: 12px; }
                .swatch span { margin-top: 3px; font: 11px ui-monospace,monospace; }
                .swatch small { margin-top: 2px; color: #eee; font-size: 10px; }
                .none { background: #202327; color: #aeb3ba; text-shadow: none; }
              </style>
            </head>
            <body>
              <header><h1>Artwork accent candidates</h1>
                <p>${rows.size} albums · macOS V2 port, AndroidX Palette, Material UI seed, and public WallpaperColors</p>
              </header>
              <main>
            """.trimIndent(),
        )
        rows.forEach { row ->
            append("<article>")
            if (row.imageData != null) {
                append("<img class=\"cover\" loading=\"lazy\" src=\"")
                append(row.imageData)
                append("\" alt=\"\">")
            } else {
                append("<div class=\"cover missing\">Artwork unavailable</div>")
            }
            append("<div class=\"meta\"><h2>").append(row.album.name.escapeHtml()).append("</h2>")
            append("<p class=\"artist\">").append(row.album.artist.escapeHtml()).append("</p>")
            row.error?.let { append("<p class=\"error\">").append(it.escapeHtml()).append("</p>") }
            append("</div><div class=\"colors\">")
            appendSwatch("macOS V2", row.macV2)
            appendSwatch("Palette dominant", row.paletteDominant)
            appendSwatch("Palette vibrant", row.paletteVibrant)
            appendSwatch("Palette muted", row.paletteMuted)
            appendSwatch("Material seed", row.materialSeed)
            appendSwatch("Wallpaper primary", row.androidPrimary)
            appendSwatch("Wallpaper secondary", row.androidSecondary)
            appendSwatch("Wallpaper tertiary", row.androidTertiary)
            append("</div></article>")
        }
        append("</main></body></html>")
    }

    private fun StringBuilder.appendSwatch(label: String, color: Int?) =
        appendSwatch(label, color?.let(::ColorCandidate))

    private fun StringBuilder.appendSwatch(label: String, candidate: ColorCandidate?) {
        if (candidate == null) {
            append("<div class=\"swatch none\"><b>").append(label).append("</b><span>—</span></div>")
            return
        }
        val hex = candidate.color.toHex()
        append("<div class=\"swatch\" style=\"background:").append(hex).append("\"><b>")
            .append(label).append("</b><span>").append(hex).append("</span>")
        candidate.populationPercent?.let {
            append("<small>").append(String.format(Locale.ROOT, "%.1f%% sample", it)).append("</small>")
        }
        append("</div>")
    }

    private fun Bitmap.jpegDataUrl(): String {
        val output = ByteArrayOutputStream()
        check(compress(Bitmap.CompressFormat.JPEG, 82, output)) { "Could not encode artwork" }
        return "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun Palette.Swatch?.toCandidate(totalPopulation: Int): ColorCandidate? = this?.let {
        ColorCandidate(
            color = rgb,
            populationPercent = if (totalPopulation > 0) population * 100.0 / totalPopulation else null,
        )
    }

    private fun Int.toHex(): String = String.format(
        Locale.ROOT,
        "#%02X%02X%02X",
        Color.red(this),
        Color.green(this),
        Color.blue(this),
    )

    private fun CodaAccentColor.toArgb(): Int = Color.rgb(
        (red.coerceIn(0.0, 1.0) * 255).roundToInt(),
        (green.coerceIn(0.0, 1.0) * 255).roundToInt(),
        (blue.coerceIn(0.0, 1.0) * 255).roundToInt(),
    )

    private fun String.escapeHtml(): String = buildString(length) {
        this@escapeHtml.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '\"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

    private data class ComparisonRow(
        val album: Album,
        val imageData: String?,
        val macV2: Int?,
        val paletteDominant: ColorCandidate?,
        val paletteVibrant: ColorCandidate?,
        val paletteMuted: ColorCandidate?,
        val materialSeed: Int?,
        val androidPrimary: Int?,
        val androidSecondary: Int?,
        val androidTertiary: Int?,
        val error: String? = null,
    ) {
        companion object {
            fun failed(album: Album, error: Throwable) = ComparisonRow(
                album = album,
                imageData = null,
                macV2 = null,
                paletteDominant = null,
                paletteVibrant = null,
                paletteMuted = null,
                materialSeed = null,
                androidPrimary = null,
                androidSecondary = null,
                androidTertiary = null,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private data class ColorCandidate(
        val color: Int,
        val populationPercent: Double? = null,
    )

    private companion object {
        const val ARGUMENT_NAME = "codaColorComparison"
        const val REFERENCE_QUERY = "All for You"
        const val REPORT_SIZE = 100
        const val COVER_SIZE = 256
        const val PALETTE_COLOR_COUNT = 128
        const val MATERIAL_COLOR_COUNT = 128
        const val REPORT_DIRECTORY = "artwork-color-comparison"
        const val REPORT_FILE = "report.html"
    }
}
