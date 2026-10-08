/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.*
import org.junit.Test

class PlaybackProjectionTest {
    private val rows = listOf(PlaylistPlaybackRow("first", "A"), PlaylistPlaybackRow("missing", null), PlaylistPlaybackRow("third", "A"))

    @Test fun missingMiddleDoesNotChangeClickTargetOrRemoveRepeat() {
        val projection = PlaybackProjection.from(rows)
        assertEquals(listOf("A", "A"), projection.occurrences.map { it.value })
        assertEquals(1, projection.startIndexFor("third"))
        assertNull(projection.startIndexFor("missing"))
        assertEquals(2, projection.occurrences.map { it.occurrenceId }.toSet().size)
    }

    @Test fun filteredViewUsesEntryIdentityInsteadOfItsRowIndex() {
        val projection = PlaybackProjection.from(rows)
        val onlyVisibleResult = rows.last()
        assertEquals(1, projection.startIndexFor(onlyVisibleResult.entryId))
    }

    @Test fun reorderedProjectionKeepsOccurrenceTargets() {
        val projection = PlaybackProjection.from(rows.reversed())
        assertEquals(0, projection.startIndexFor("third"))
        assertEquals(1, projection.startIndexFor("first"))
    }

    @Test fun everyQueueCreationGetsNewRuntimeOccurrences() {
        val first = PlaybackProjection.from(rows)
        val second = PlaybackProjection.from(rows)
        assertTrue(first.occurrences.map { it.occurrenceId }.toSet().intersect(second.occurrences.map { it.occurrenceId }.toSet()).isEmpty())
    }

    @Test fun noPlayableRowsNeverInventStartIndexZero() {
        val projection = PlaybackProjection.from(listOf(PlaylistPlaybackRow<String>("missing", null)))
        assertTrue(projection.occurrences.isEmpty())
        assertNull(projection.startIndexFor("missing"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateEntryIdsAreRejectedEvenForMissingRows() {
        PlaybackProjection.from(listOf(PlaylistPlaybackRow("same", "A"), PlaylistPlaybackRow("same", null)))
    }
}
