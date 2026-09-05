// AY -->
package eu.kanade.tachiyomi.data.track.local

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

/**
 * Minimal AniList metadata client using the public GraphQL API (no auth required).
 * Used to build local-anime metadata when the user tracks through AniList.
 */
class AnilistMetadataApi(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    suspend fun getAnime(anilistId: Long): AnilistMedia? {
        return withIOContext {
            val query = """
                query (${'$'}id: Int) {
                    Media(id: ${'$'}id, type: ANIME) {
                        id
                        idMal
                        title { romaji english native }
                        description
                        status
                        format
                        genres
                        coverImage { extraLarge large }
                        studios(isMain: true) { nodes { name } }
                    }
                }
            """.trimIndent()
            val body = buildJsonObject {
                put("query", query)
                put("variables", buildJsonObject { put("id", anilistId) })
            }.toString().toRequestBody(jsonMime)
            with(json) {
                client.newCall(POST(API_URL, body = body))
                    .awaitSuccess()
                    .parseAs<AnilistMediaResponse>()
                    .data
                    ?.media
            }
        }
    }

    suspend fun getMalId(anilistId: Long): Long {
        return try {
            getAnime(anilistId)?.idMal ?: 0
        } catch (_: Exception) {
            0
        }
    }

    companion object {
        const val API_URL = "https://graphql.anilist.co/"
    }
}

@Serializable
data class AnilistMediaResponse(
    val data: AnilistMediaData? = null,
)

@Serializable
data class AnilistMediaData(
    @SerialName("Media")
    val media: AnilistMedia? = null,
)

@Serializable
data class AnilistMedia(
    val id: Long = 0,
    val idMal: Long? = null,
    val title: AnilistTitle? = null,
    val description: String? = null,
    val status: String? = null,
    val format: String? = null,
    val genres: List<String> = emptyList(),
    val coverImage: AnilistCover? = null,
    val studios: AnilistStudios? = null,
) {
    val displayTitle: String
        get() = title?.english?.takeIf { it.isNotBlank() }
            ?: title?.romaji?.takeIf { it.isNotBlank() }
            ?: title?.native.orEmpty()

    val studioNames: List<String>
        get() = studios?.nodes?.mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }.orEmpty()

    val coverUrl: String?
        get() = coverImage?.extraLarge ?: coverImage?.large
}

@Serializable
data class AnilistTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class AnilistCover(
    val extraLarge: String? = null,
    val large: String? = null,
)

@Serializable
data class AnilistStudios(
    val nodes: List<AnilistStudio> = emptyList(),
)

@Serializable
data class AnilistStudio(
    val name: String? = null,
)
// <-- AY
