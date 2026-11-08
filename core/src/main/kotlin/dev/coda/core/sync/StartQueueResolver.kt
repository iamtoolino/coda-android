package dev.coda.core.sync

import dev.coda.core.subsonic.Album
import dev.coda.core.subsonic.AlbumListType
import dev.coda.core.subsonic.PlayQueue
import dev.coda.core.subsonic.SubsonicError
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException

/**
 * The one place that decides what "play music on Coda" without a title (empty voice query)
 * and Android's playback resumption play. Voice takes the first available of:
 * 1. our own queue (the player's, else the local copy, else a server queue written by this phone itself, for example
 *    right after the update when there is no local copy yet), resumed at its saved position;
 * 2. the server's saved queue written by another device: the explicit request counts as the tap on the
 *    handoff card, so the "never applied automatically" holds;
 * 3. the [NEWEST_COUNT] newest unrated albums (assumed unheard), in order;
 * 4. if fewer: top-rated albums ([TOP_RATING]★ or more) added in the last year, only when the library has at least
 *    [MIN_RATED_ALBUMS] rated albums;
 * 5. random albums for the remaining places, so the request never plays nothing.
 * Resumption plays only step 1 (never the handoff queue unseen), else steps 3–5. Continue Listening is not part of
 * the chain (it may have been stopped on purpose).
 */
class StartQueueResolver(
    private val sources: Sources,
    /** This phone's client name: a server queue it wrote itself is our own queue (step 1), not a handoff. */
    private val clientName: String = "",
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class Reason { VOICE, RESUMPTION }

    interface Sources {
        /** Our own queue: the one in the player, else the local copy of our last saved queue. Null when none. */
        suspend fun ownQueue(): OwnQueue?
        /** The server's one saved queue. */
        suspend fun serverQueue(): PlayQueue?
        /** One page of `getAlbumList2`. */
        suspend fun albumList(type: AlbumListType, size: Int, offset: Int): List<Album>
    }

    sealed interface Choice {
        data class Own(val queue: OwnQueue) : Choice
        data class Handoff(val queue: PlayQueue) : Choice
        /** Step 1 from the server: the saved queue was written by this phone (its own client name), no local copy yet. */
        data class OwnOnServer(val queue: PlayQueue) : Choice
        /** Albums to queue in this order; the counts say which step supplied them (newest, then top-rated, then random). */
        data class Albums(val albums: List<Album>, val newest: Int, val topRated: Int, val random: Int) : Choice
    }

    companion object {
        const val NEWEST_COUNT = 4
        const val MIN_RATED_ALBUMS = 10
        const val TOP_RATING = 4
        const val TOP_RATED_WINDOW_DAYS = 365L
        const val PAGE = 100
        /** Upper bound per list, so a huge or oddly sorted library cannot make a voice request page forever. */
        const val MAX_SCAN = 500
        const val RANDOM_PAGE = 20

        /** `MediaStore.EXTRA_MEDIA_FOCUS`, and its "anything" value. */
        const val EXTRA_MEDIA_FOCUS = "android.intent.extra.focus"
        const val FOCUS_ANY = "vnd.android.cursor.item/*"
        private val GENERIC_QUERIES = setOf("music", "some music", "songs", "some songs", "something", "anything")

        /**
         * A voice request that names nothing ("play music on Coda" may arrive as "" or "music", or with the
         * "any" focus) plays the start chain, not a title search for the word "music".
         */
        fun isGeneric(query: String?, focus: String?): Boolean =
            focus == FOCUS_ANY || query.isNullOrBlank() ||
                query.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT) in GENERIC_QUERIES

        private fun rating(album: Album) = album.userRating ?: 0

        /** `created` is ISO 8601 (with or without offset or time); an unreadable date never counts as recent. */
        fun createdMs(album: Album): Long? {
            val value = album.created?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        }
    }

    suspend fun resolve(reason: Reason): Choice? {
        sources.ownQueue()?.takeIf { it.entries.isNotEmpty() }?.let { return Choice.Own(it) }
        // Resumption stays local-only: it never reads the server queue.
        if (reason == Reason.VOICE) server()?.let {
            return if (clientName.isNotEmpty() && it.changedBy.equals(clientName, ignoreCase = true)) Choice.OwnOnServer(it) else Choice.Handoff(it)
        }
        return albums()
    }

    /** A server queue that cannot be read is a missing step; a rejected login is not. */
    private suspend fun server(): PlayQueue? = try {
        sources.serverQueue()?.takeIf { it.entry.isNotEmpty() }
    } catch (e: CancellationException) { throw e }
    catch (e: SubsonicError.Unauthorized) { throw e }
    catch (_: Exception) { null }

    private suspend fun albums(): Choice.Albums? {
        val picked = LinkedHashMap<String, Album>()
        fun unrated(album: Album) = rating(album) == 0

        // A failing newest list is a missing step like the top-rated one: the top-up and random albums still play.
        // (A server that doesn't answer at all still fails the random list, so the car falls back to the phone.)
        val newest = (try {
            scan(AlbumListType.NEWEST) { seen -> seen.count(::unrated) >= NEWEST_COUNT }
        } catch (e: CancellationException) { throw e }
        catch (e: SubsonicError.Unauthorized) { throw e }
        catch (_: Exception) { emptyList() })
            .filter(::unrated).distinctBy { it.id }.take(NEWEST_COUNT)
        newest.forEach { picked[it.id] = it }

        var topRated = 0
        if (picked.size < NEWEST_COUNT) {
            // "highest" lists rated albums first, best first: stop once the 4★ albums are behind us and the rated count is known.
            // The 500 cap and a failing list both end the top-up with what was read: random albums still fill the places.
            val list = try {
                scan(AlbumListType.TOP_RATED) { seen ->
                    val last = seen.lastOrNull()?.let(::rating) ?: 0
                    last < TOP_RATING && (last == 0 || seen.count { rating(it) > 0 } >= MIN_RATED_ALBUMS)
                }.distinctBy { it.id }
            } catch (e: CancellationException) { throw e }
            catch (e: SubsonicError.Unauthorized) { throw e }
            catch (_: Exception) { emptyList() }
            if (list.count { rating(it) > 0 } >= MIN_RATED_ALBUMS) {
                val since = nowMs() - TOP_RATED_WINDOW_DAYS * 24 * 60 * 60 * 1000
                list.filter { rating(it) >= TOP_RATING && it.id !in picked && (createdMs(it) ?: Long.MIN_VALUE) >= since }
                    .sortedWith(compareByDescending<Album> { rating(it) }.thenByDescending { createdMs(it) })
                    .take(NEWEST_COUNT - picked.size)
                    .forEach { picked[it.id] = it; topRated++ }
            }
        }

        var random = 0
        if (picked.size < NEWEST_COUNT) {
            // With albums already chosen the random list is only a top-up: its failure keeps them (a rejected login still fails).
            // With none chosen it still fails, so the car falls back to the phone.
            val list = try { sources.albumList(AlbumListType.RANDOM, RANDOM_PAGE, 0) }
            catch (e: CancellationException) { throw e }
            catch (e: SubsonicError.Unauthorized) { throw e }
            catch (e: Exception) { if (picked.isEmpty()) throw e else emptyList() }
            list.filter { it.id !in picked }.distinctBy { it.id }
                .take(NEWEST_COUNT - picked.size).forEach { picked[it.id] = it; random++ }
        }
        return picked.values.toList().takeIf { it.isNotEmpty() }?.let { Choice.Albums(it, newest.size, topRated, random) }
    }

    private suspend fun scan(type: AlbumListType, enough: (List<Album>) -> Boolean): List<Album> {
        val seen = mutableListOf<Album>()
        while (seen.size < MAX_SCAN) {
            val page = sources.albumList(type, PAGE, seen.size)
            seen += page
            if (page.size < PAGE || enough(seen)) break
        }
        return seen
    }
}
