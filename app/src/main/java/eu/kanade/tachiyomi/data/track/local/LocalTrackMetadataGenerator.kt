// AY -->
package eu.kanade.tachiyomi.data.track.local

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.tachiyomi.AnimeDetails
import tachiyomi.source.local.io.LocalSourceFileSystem
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Automatically fills the Aniyomi local-anime metadata files (`details.json`
 * plus `cover.jpg`) for a locally stored anime right after the user links it
 * to a tracking service entry.
 *
 * ID resolution gives priority to MyAnimeList, mirroring the reference
 * aniyomi-local-manager flow:
 * - MyAnimeList track -> MAL id is the track's remote id.
 * - AniList track -> AniList id is the track's remote id, metadata fetched
 *   from AniList GraphQL (no login required).
 * - Simkl track -> MAL id resolved through Simkl's id-lookup endpoint with a
 *   Jikan title-search fallback ([SimklIdResolver]).
 *
 * Files are written exactly where [tachiyomi.source.local.LocalSource] reads
 * them from (`localanime/<AnimeDir>/details.json`), so no manual steps remain.
 */
class LocalTrackMetadataGenerator(
    private val client: OkHttpClient = OkHttpClient(),
    private val fileSystem: LocalSourceFileSystem = Injekt.get(),
) {

    private val json: Json by injectLazy()
    private val jikanApi = JikanApi(client)
    private val anilistApi = AnilistMetadataApi(client)
    private val simklResolver = SimklIdResolver(client)

    sealed interface FillResult {
        data object Success : FillResult
        data object MalIdUnavailable : FillResult
        data object UnsupportedTracker : FillResult
        data class Failed(val reason: String? = null) : FillResult
    }

    suspend fun fillFromTrack(animeUrl: String, trackerId: Long, track: TrackSearch): FillResult {
        return withIOContext {
            try {
                val metadata = when (trackerId) {
                    // No MAL constant exists in TrackerManager; MyAnimeList is always id 1.
                    1L -> metadataFromMal(track.remote_id)
                    TrackerManager.ANILIST -> metadataFromAnilist(track.remote_id)
                    TrackerManager.SIMKL -> metadataFromSimkl(track)
                    else -> return@withIOContext FillResult.UnsupportedTracker
                } ?: return@withIOContext FillResult.Failed()

                writeDetailsJson(animeUrl, metadata.details)
                writeCoverIfMissing(animeUrl, metadata.coverUrl)

                FillResult.Success
            } catch (e: MalIdUnavailableException) {
                logcat(LogPriority.WARN, e) { "LocalTrackMetadataGenerator: no MAL id for url=$animeUrl" }
                FillResult.MalIdUnavailable
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "LocalTrackMetadataGenerator: failed for url=$animeUrl" }
                FillResult.Failed(e.message)
            }
        }
    }

    private suspend fun metadataFromMal(malId: Long): ResolvedMetadata? {
        if (malId <= 0) return null
        delay(SimklIdResolver.JIKAN_DELAY_MS)
        return jikanApi.getAnime(malId).toResolvedMetadata()
    }

    private suspend fun metadataFromAnilist(anilistId: Long): ResolvedMetadata? {
        if (anilistId <= 0) return null
        return anilistApi.getAnime(anilistId)?.toResolvedMetadata()
    }

    private suspend fun metadataFromSimkl(track: TrackSearch): ResolvedMetadata? {
        val malId = simklResolver.resolveMalId(track.remote_id, track.title)
        if (malId <= 0) throw MalIdUnavailableException()
        delay(SimklIdResolver.JIKAN_DELAY_MS)
        return jikanApi.getAnime(malId).toResolvedMetadata()
    }

    private fun writeDetailsJson(animeUrl: String, details: AnimeDetails) {
        val directory = fileSystem.getAnimeDirectory(animeUrl)
            ?: throw IllegalStateException("Local anime directory not found")
        val existing = directory.listFiles()
            ?.find { it.extension == "json" && it.nameWithoutExtension == "details" }
        val file = existing ?: directory.createFile(DETAILS_FILE)
            ?: throw IllegalStateException("Could not create $DETAILS_FILE")
        file.openOutputStream()?.use { json.encodeToStream(details, it) }
            ?: throw IllegalStateException("Could not write $DETAILS_FILE")
    }

    private fun writeCoverIfMissing(animeUrl: String, coverUrl: String?) {
        if (coverUrl.isNullOrBlank()) return
        val directory = fileSystem.getAnimeDirectory(animeUrl) ?: return
        val hasCover = directory.listFiles()
            ?.any { it.nameWithoutExtension == "cover" } == true
        if (hasCover) return
        val bytes = client.newCall(GET(coverUrl)).awaitSuccess().use { it.body.bytes() }
        val file = directory.createFile(COVER_FILE) ?: return
        file.openOutputStream()?.use { it.write(bytes) }
    }

    private fun JikanAnime.toResolvedMetadata(): ResolvedMetadata {
        val genres = (genres.map { it.name } + explicitGenres.map { it.name })
            .filter { it.isNotBlank() }
            .distinct()
        return ResolvedMetadata(
            details = AnimeDetails(
                title = displayTitle.takeIf { it.isNotBlank() },
                author = studios.map { it.name }.filter { it.isNotBlank() }
                    .joinToString().takeIf { it.isNotBlank() },
                description = synopsis?.cleanDescription(),
                genre = genres.takeIf { it.isNotEmpty() },
                status = mapJikanStatus(status),
            ),
            coverUrl = coverUrl,
        )
    }

    private fun AnilistMedia.toResolvedMetadata(): ResolvedMetadata {
        return ResolvedMetadata(
            details = AnimeDetails(
                title = displayTitle.takeIf { it.isNotBlank() },
                author = studioNames.joinToString().takeIf { it.isNotBlank() },
                description = description?.cleanDescription(),
                genre = buildList {
                    format?.takeIf { it.isNotBlank() }?.let { add(it) }
                    addAll(genres.filter { it.isNotBlank() })
                }.distinct().takeIf { it.isNotEmpty() },
                status = mapAnilistStatus(status),
            ),
            coverUrl = coverUrl,
        )
    }

    private fun mapJikanStatus(status: String?): Int {
        return when (status?.lowercase()) {
            "finished airing" -> SAnime.COMPLETED
            "currently airing" -> SAnime.ONGOING
            "not yet aired" -> SAnime.UPCOMING
            "on hiatus" -> SAnime.ON_HIATUS
            "discontinued" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }

    private fun mapAnilistStatus(status: String?): Int {
        return when (status) {
            "FINISHED" -> SAnime.COMPLETED
            "RELEASING" -> SAnime.ONGOING
            "NOT_YET_RELEASED" -> SAnime.UPCOMING
            "HIATUS" -> SAnime.ON_HIATUS
            "CANCELLED" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.cleanDescription(): String? {
        // AniList returns HTML; Jikan appends a "[Written by MAL Rewrite]" credit line.
        return replace("<br>", "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace(Regex("\\[Written by MAL Rewrite\\].*$"), "")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private data class ResolvedMetadata(
        val details: AnimeDetails,
        val coverUrl: String?,
    )

    class MalIdUnavailableException : Exception()

    companion object {
        const val DETAILS_FILE = "details.json"
        const val COVER_FILE = "cover.jpg"
    }
}
// <-- AY
