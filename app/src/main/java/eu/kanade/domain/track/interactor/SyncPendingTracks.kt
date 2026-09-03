package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.tachiyomi.data.track.TrackerManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.interactor.GetTracks

/**
 * Pushes every locally seen episode that no tracker has recorded yet.
 * Runs when the app comes to the foreground (once connectivity is
 * available), so episodes watched offline are synced with the tracking
 * services as soon as the device is back online.
 */
class SyncPendingTracks(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val trackEpisode: TrackEpisode,
) {

    suspend fun await(context: Context) {
        withNonCancellableContext {
            val tracksByAnime = try {
                getTracks.await()
                    .filter { trackerManager.get(it.trackerId)?.isLoggedIn == true }
                    .groupBy { it.animeId }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                return@withNonCancellableContext
            }
            tracksByAnime.forEach { (animeId, tracks) ->
                try {
                    val ceiling = tracks.maxOf { it.lastEpisodeSeen }
                    val maxMissing = getEpisodesByAnimeId.await(animeId)
                        .filter { it.seen && it.isRecognizedNumber }
                        .maxOfOrNull { it.episodeNumber }
                        ?.takeIf { it > ceiling }
                        ?: return@forEach
                    // TrackEpisode backfills every missing episode with its
                    // own watch date and queues the retry on failure.
                    trackEpisode.await(context, animeId, maxMissing)
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e)
                }
            }
        }
    }
}
