// AY -->
package eu.kanade.tachiyomi.data.track.local

import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

/**
 * Minimal MyAnimeList metadata client backed by the public Jikan API.
 *
 * The official MAL API requires OAuth, so it cannot be used when the user only
 * tracks through Simkl/AniList. Jikan needs no authentication, which mirrors
 * the approach of the aniyomi-local-manager reference implementation.
 */
class JikanApi(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    suspend fun searchAnime(query: String, limit: Int = 5): List<JikanAnime> {
        return withIOContext {
            val url = "$BASE_URL/anime".toUri().buildUpon()
                .appendQueryParameter("q", query.take(64))
                .appendQueryParameter("limit", limit.coerceIn(1, 25).toString())
                .appendQueryParameter("order_by", "members")
                .appendQueryParameter("sort", "desc")
                .build()
            with(json) {
                client.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<JikanSearchResponse>()
                    .data
            }
        }
    }

    suspend fun getAnime(malId: Long): JikanAnime {
        return withIOContext {
            with(json) {
                client.newCall(GET("$BASE_URL/anime/$malId"))
                    .awaitSuccess()
                    .parseAs<JikanDetailResponse>()
                    .data
            }
        }
    }

    companion object {
        const val BASE_URL = "https://api.jikan.moe/v4"
    }
}

@Serializable
data class JikanSearchResponse(
    val data: List<JikanAnime> = emptyList(),
)

@Serializable
data class JikanDetailResponse(
    val data: JikanAnime,
)

@Serializable
data class JikanAnime(
    @SerialName("mal_id")
    val malId: Long = 0,
    val title: String? = null,
    @SerialName("title_english")
    val titleEnglish: String? = null,
    val synopsis: String? = null,
    val status: String? = null,
    val genres: List<JikanNamed> = emptyList(),
    @SerialName("explicit_genres")
    val explicitGenres: List<JikanNamed> = emptyList(),
    val studios: List<JikanNamed> = emptyList(),
    val images: JikanImages? = null,
) {
    val displayTitle: String
        get() = titleEnglish?.takeIf { it.isNotBlank() } ?: title.orEmpty()

    val coverUrl: String?
        get() = images?.jpg?.largeImageUrl
            ?: images?.jpg?.imageUrl
            ?: images?.webp?.largeImageUrl
}

@Serializable
data class JikanNamed(
    val name: String = "",
)

@Serializable
data class JikanImages(
    val jpg: JikanImageUrls? = null,
    val webp: JikanImageUrls? = null,
)

@Serializable
data class JikanImageUrls(
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("large_image_url")
    val largeImageUrl: String? = null,
)
// <-- AY
