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
 * Simkl maintains MAL IDs for a part of its catalogue and exposes them through
 * `GET /search/id?simkl=<id>`. Anime entries frequently have no cross IDs at
 * all, so when the direct lookup yields nothing we fall back to a Jikan title
 * search (results ordered by member count, first hit wins).
 *
 * @return the MAL id, or 0 when no MAL id could be resolved.
 */
class SimklIdResolver(private val client: OkHttpClient) {

    private val json: Json by injectLazy()
    private val jikanApi = JikanApi(client)

    suspend fun resolveMalId(simklId: Long, titleFallback: String? = null): Long {
        return withIOContext {
            directLookup(simklId).takeIf { it > 0 }
                ?: titleLookup(titleFallback)
        }
    }

    private suspend fun directLookup(simklId: Long): Long {
        return try {
            val url = "${SIMKL_ID_LOOKUP_URL}?simkl=$simklId&client_id=${SimklApi.CLIENT_ID}"
            with(json) {
                client.newCall(GET(url))
                    .awaitSuccess()
                    .parseAs<List<SimklIdLookupResult>>()
                    .firstOrNull()
                    ?.extractMalId() ?: 0
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "SimklIdResolver: direct MAL lookup failed for simkl=$simklId" }
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
        const val SIMKL_ID_LOOKUP_URL = "https://api.simkl.com/search/id"
        const val JIKAN_DELAY_MS = 400L
    }
}

@Serializable
data class SimklIdLookupResult(
    val ids: JsonObject? = null,
) {
    /**
     * Extracts the MAL id from the cross-ID block. Simkl is inconsistent here:
     * `mal` may be absent, a bare number/string id, or an object holding `id`.
     */
    fun extractMalId(): Long {
        val mal = ids?.get("mal") ?: return 0
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
}
// <-- AY
