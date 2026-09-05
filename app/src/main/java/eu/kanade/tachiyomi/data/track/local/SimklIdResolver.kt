// AY -->
package eu.kanade.tachiyomi.data.track.local

import eu.kanade.tachiyomi.data.track.simkl.SimklApi
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import logcat.LogPriority
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy

/**
 * Resolves the MyAnimeList ID for a Simkl entry.
 *
 * The id-lookup endpoint (`GET /search/id?simkl=<id>`) only returns cross IDs
 * for part of the catalogue — anime entries typically carry just the Simkl id.
 * The full detail endpoint (`GET /anime|shows|movies/<id>?extended=full`),
 * the same source the Simkl website itself uses, exposes the complete cross-ID
 * block including `mal`/`anilist`. Only when that yields nothing do we fall
 * back to a Jikan title search (results ordered by member count, first hit
 * wins).
 *
 * @return the MAL id, or 0 when no MAL id could be resolved.
 */
class SimklIdResolver(private val client: OkHttpClient) {

    private val json: Json by injectLazy()
    private val jikanApi = JikanApi(client)

    suspend fun resolveMalId(
        simklId: Long,
        titleFallback: String? = null,
        trackingUrl: String? = null,
    ): Long {
        return withIOContext {
            detailLookup(simklId, trackingUrl).takeIf { it > 0 }
                ?: idLookup(simklId).takeIf { it > 0 }
                ?: titleLookup(titleFallback)
        }
    }

    /**
     * Primary source: the detail endpoint carries the full `ids` block
     * (`mal`, `anilist`, `kitsu`, …). The endpoint segment is derived from the
     * tracked entry's URL (`/anime/…`, `/tv/…`, `/movies/…`); other segments
     * are tried as fallback since Simkl answers 404/redirects for mismatches.
     */
    private suspend fun detailLookup(simklId: Long, trackingUrl: String?): Long {
        for (endpoint in endpointsFor(trackingUrl)) {
            val malId = try {
                val url = "$API_URL/$endpoint/$simklId?extended=full&client_id=${SimklApi.CLIENT_ID}"
                with(json) {
                    client.newCall(GET(url))
                        .awaitSuccess()
                        .parseAs<SimklDetailResult>()
                        .extractMalId()
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) {
                    "SimklIdResolver: detail lookup failed for simkl=$simklId ($endpoint)"
                }
                0
            }
            if (malId > 0) return malId
        }
        return 0
    }

    private fun endpointsFor(trackingUrl: String?): List<String> {
        return when (trackingUrl?.substringAfter("/")?.substringBefore("/")?.lowercase()) {
            "movies", "movie" -> listOf("movies", "anime", "shows")
            "tv", "shows", "show" -> listOf("shows", "anime", "movies")
            else -> listOf("anime", "shows", "movies")
        }
    }

    private suspend fun idLookup(simklId: Long): Long {
        return try {
            val url = "$SIMKL_ID_LOOKUP_URL?simkl=$simklId&client_id=${SimklApi.CLIENT_ID}"
            with(json) {
                client.newCall(GET(url))
                    .awaitSuccess()
                    .parseAs<List<SimklIdLookupResult>>()
                    .firstOrNull()
                    ?.extractMalId() ?: 0
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "SimklIdResolver: id lookup failed for simkl=$simklId" }
            0
        }
    }

    private suspend fun titleLookup(title: String?): Long {
        val query = title?.takeIf { it.isNotBlank() } ?: return 0
        return try {
            // Courtesy delay: Jikan is rate limited (3 req/s).
            delay(JIKAN_DELAY_MS)
            jikanApi.searchAnime(query, limit = 1).firstOrNull()?.malId ?: 0
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "SimklIdResolver: Jikan title lookup failed for \"$query\"" }
            0
        }
    }

    companion object {
        const val API_URL = "https://api.simkl.com"
        const val SIMKL_ID_LOOKUP_URL = "https://api.simkl.com/search/id"
        const val JIKAN_DELAY_MS = 400L
    }
}

/**
 * Extracts the MAL id from a Simkl cross-ID block. The value is inconsistent:
 * `mal` may be absent, a bare number/string id, or an object holding `id`.
 */
private fun JsonObject.extractMalIdValue(): Long {
    val mal = get("mal") ?: return 0
    return try {
        if (mal is JsonObject) {
            mal["id"]?.jsonPrimitive?.longOrNull ?: 0
        } else {
            mal.jsonPrimitive.longOrNull ?: 0
        }
    } catch (_: Exception) {
        0
    }
}

@Serializable
data class SimklDetailResult(
    val ids: JsonObject? = null,
) {
    fun extractMalId(): Long = ids?.extractMalIdValue() ?: 0
}

@Serializable
data class SimklIdLookupResult(
    val ids: JsonObject? = null,
) {
    fun extractMalId(): Long = ids?.extractMalIdValue() ?: 0
}
// <-- AY
