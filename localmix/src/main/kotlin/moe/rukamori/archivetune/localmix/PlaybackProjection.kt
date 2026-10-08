/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.util.UUID

data class PlaylistPlaybackRow<out T>(val entryId: String, val playable: T?)
data class QueueOccurrence<out T>(val occurrenceId: String, val entryId: String, val value: T)

/** Preserves intentional repeats, while missing/excluded rows stay outside playback. */
class PlaybackProjection<T> private constructor(val occurrences: List<QueueOccurrence<T>>) {
    private val indexByEntry = occurrences.mapIndexed { index, occurrence -> occurrence.entryId to index }.toMap()

    fun startIndexFor(entryId: String): Int? = indexByEntry[entryId]

    companion object {
        fun <T> from(rows: List<PlaylistPlaybackRow<T>>): PlaybackProjection<T> {
            require(rows.map { it.entryId }.toSet().size == rows.size) { "Playlist entry IDs must be unique" }
            return PlaybackProjection(
                rows.mapNotNull { row ->
                    row.playable?.let { QueueOccurrence(UUID.randomUUID().toString(), row.entryId, it) }
                },
            )
        }
    }
}
