package eu.kanade.domain.track.store

import android.content.Context
import androidx.core.content.edit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class DelayedTrackingStore(context: Context) {

    /**
     * Preference file where queued tracking updates are stored.
     */
    private val preferences = context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE)

    fun add(trackId: Long, lastEpisodeSeen: Double, watchedAt: Long = 0) {
        val previousLastEpisodeSeen = preferences.getFloat(trackId.toString(), 0f)
        if (lastEpisodeSeen > previousLastEpisodeSeen) {
            logcat(LogPriority.DEBUG) { "Queuing track item: $trackId, last episode seen: $lastEpisodeSeen" }
            preferences.edit {
                putFloat(trackId.toString(), lastEpisodeSeen.toFloat())
                if (watchedAt > 0) {
                    putLong(trackId.watchedAtKey(), watchedAt)
                }
            }
        }
    }

    fun remove(trackId: Long) {
        preferences.edit {
            remove(trackId.toString())
            remove(trackId.watchedAtKey())
        }
    }

    fun getItems(): List<DelayedTrackingItem> {
        return preferences.all.mapNotNull { (key, value) ->
            if (key.endsWith(WATCHED_AT_SUFFIX)) return@mapNotNull null
            val trackId = key.toLongOrNull() ?: return@mapNotNull null
            DelayedTrackingItem(
                trackId = trackId,
                lastEpisodeSeen = value.toString().toFloatOrNull() ?: 0f,
                watchedAt = preferences.getLong(trackId.watchedAtKey(), 0L),
            )
        }
    }

    data class DelayedTrackingItem(
        val trackId: Long,
        val lastEpisodeSeen: Float,
        val watchedAt: Long = 0L,
    )

    companion object {
        private const val WATCHED_AT_SUFFIX = ":watched_at"

        private fun Long.watchedAtKey(): String = "$this$WATCHED_AT_SUFFIX"
    }
}
