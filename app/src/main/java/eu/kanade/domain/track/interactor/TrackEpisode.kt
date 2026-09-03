package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack

class TrackEpisode(
    private val getTracks: GetTracks,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertTrack,
    private val delayedTrackingStore: DelayedTrackingStore,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val getHistory: GetHistory,
) {

    suspend fun await(
        context: Context,
        animeId: Long,
        episodeNumber: Double,
        setupJobOnFailure: Boolean = true,
        watchedAt: Long = 0,
    ) {
        withNonCancellableContext {
            val tracks = getTracks.await(animeId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            // First-watch dates of locally seen episodes, used to backfill
            // every episode the tracker has not recorded yet, so no episode
            // watched offline is ever skipped on the remote service.
            val seenDates: Map<Double, Long> = try {
                val historyByEpisodeId = getHistory.await(animeId)
                    .associate { it.episodeId to (it.firstSeen?.time ?: it.seenAt?.time) }
                getEpisodesByAnimeId.await(animeId)
                    .filter { it.seen && it.isRecognizedNumber }
                    .associate {
                        val date = historyByEpisodeId[it.id] ?: watchedAt
                        val seenAt = if (date > 0) date else System.currentTimeMillis()
                        it.episodeNumber to seenAt
                    }
            } catch (_: Exception) {
                emptyMap()
            }

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                if (service == null || !service.isLoggedIn || episodeNumber <= track.lastEpisodeSeen) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            val refreshed = service.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                            // Never move the counter backwards: the remote
                            // side may already be ahead of this update.
                            val target = maxOf(episodeNumber, refreshed.lastEpisodeSeen)
                            val missing = seenDates
                                .filterKeys { it > refreshed.lastEpisodeSeen && it <= target }
                                .toSortedMap()
                            if (service.supportsPerEpisodeTracking && missing.isNotEmpty()) {
                                missing.forEach { (number, seenAt) ->
                                    val event = refreshed.copy(lastEpisodeSeen = number)
                                    service.update(event.toDbTrack(), true, seenAt)
                                }
                            } else if (target > refreshed.lastEpisodeSeen) {
                                val event = refreshed.copy(lastEpisodeSeen = target)
                                service.update(event.toDbTrack(), true, watchedAt)
                            }
                            val updatedTrack = refreshed.copy(lastEpisodeSeen = target)
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, episodeNumber, watchedAt)
                            if (setupJobOnFailure) {
                                DelayedTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.WARN, it) }
        }
    }
}
