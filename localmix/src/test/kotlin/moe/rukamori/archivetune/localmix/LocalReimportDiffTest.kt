/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.*
import org.junit.Test

class LocalReimportDiffTest {
    private val a = LocalManifestItem("Music/a.mp3", "A", 123.0)
    private val b = LocalManifestItem("Music/b.mp3", "B", 234.0)
    private val c = LocalManifestItem("Music/c.mp3")

    @Test fun identicalManifestKeepsEveryOccurrenceWithoutChanges() {
        val rows = listOf(a, b, a, a)
        val diff = LocalReimportDiff.between(rows, rows)
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertTrue(diff.moved.isEmpty())
    }

    @Test fun insertionsAndDeletionsDoNotPretendAllFollowingRowsWereMoved() {
        val diff = LocalReimportDiff.between(listOf(a, b, a), listOf(c, a, a))
        assertEquals(listOf(LocalManifestOccurrence(0, c)), diff.added)
        assertEquals(listOf(LocalManifestOccurrence(1, b)), diff.removed)
        assertTrue(diff.moved.isEmpty())
    }

    @Test fun repeatedRowsHaveSeparateAddedAndRemovedPositions() {
        val added = LocalReimportDiff.between(listOf(b, a), listOf(b, a, a, a))
        assertEquals(listOf(2, 3), added.added.map { it.position })
        assertTrue(added.moved.isEmpty())
        val removed = LocalReimportDiff.between(listOf(a, b, a, a), listOf(a, b))
        assertEquals(listOf(2, 3), removed.removed.map { it.position })
        assertTrue(removed.moved.isEmpty())
    }

    @Test fun reorderPairsRepeatedOccurrencesDeterministically() {
        val diff = LocalReimportDiff.between(listOf(a, b, a), listOf(a, a, b))
        assertTrue(diff.added.isEmpty())
        assertTrue(diff.removed.isEmpty())
        assertEquals(listOf(LocalManifestMove(2, 1, a), LocalManifestMove(1, 2, b)), diff.moved)
    }

    @Test fun extinfEditIsAnExplicitReplacement() {
        val edited = a.copy(title = "A live", durationSeconds = 125.0)
        val diff = LocalReimportDiff.between(listOf(a, b), listOf(edited, b))
        assertEquals(listOf(LocalManifestOccurrence(0, a)), diff.removed)
        assertEquals(listOf(LocalManifestOccurrence(0, edited)), diff.added)
        assertTrue(diff.moved.isEmpty())
    }

    @Test fun pathCaseAndDirectoriesAreNotGuessedToBeTheSameEntry() {
        val next = listOf(a.copy(path = "Music/A.mp3"), a.copy(path = "Other/a.mp3"))
        val diff = LocalReimportDiff.between(listOf(a, a), next)
        assertEquals(2, diff.added.size)
        assertEquals(2, diff.removed.size)
    }
}

class LocalLocatorFallbackTest {
    private val entry = M3uEntry(4, 10, "../Album/a.mp3", M3uLocationKind.LOCAL_PATH, "A", 123.0)
    private val locator = LocalFileLocator("external_primary", "Music/Album", "a.mp3")
    private val indexed = IndexedLocalFile("content://media/external/audio/media/99", locator)

    @Test fun sourceLossUsesSavedExactLocatorAndRechecksFreshMediaStoreId() {
        val restored = LocalLocatorFallback.entryFor(entry, null, locator)
        assertEquals("/storage/emulated/0/Music/Album/a.mp3", restored.location)
        assertEquals(entry.ordinal, restored.ordinal)
        assertEquals(entry.title, restored.title)
        assertEquals(LocalPathResolution.Resolved(indexed), LocalPathIndex(listOf(indexed)).resolve(restored))
        assertEquals(LocalPathResolution.Missing(locator), LocalPathIndex(emptyList()).resolve(restored))
    }

    @Test fun retainedParentKeepsOriginalRelativeResolution() {
        val parent = PlaylistParent("external_primary", "Music/Playlists")
        assertEquals(entry, LocalLocatorFallback.entryFor(entry, parent, locator))
        assertEquals(LocalPathResolution.Resolved(indexed), LocalPathIndex(listOf(indexed)).resolve(entry, parent))
    }

    @Test fun unavailableLocatorDoesNotBecomeAnArbitraryBasenameMatch() {
        assertEquals(entry, LocalLocatorFallback.entryFor(entry, null, null))
        assertEquals(LocalPathResolution.ParentRequired, LocalPathIndex(listOf(indexed)).resolve(entry))
    }

    @Test fun absoluteRemoteAndWindowsPathsAreNotOverriddenBySavedLocator() {
        listOf(
            entry.copy(location = "/other/a.mp3"),
            entry.copy(location = "https://example.com/a.mp3", kind = M3uLocationKind.REMOTE_URI),
            entry.copy(location = "C:/Music/a.mp3"),
            entry.copy(location = "C:" + '\\' + "Music" + '\\' + "a.mp3"),
        ).forEach { original -> assertEquals(original, LocalLocatorFallback.entryFor(original, null, locator)) }
    }

    @Test fun removableVolumeLocatorRemainsBoundToThatVolume() {
        val sd = locator.copy(volume = "abcd-1234")
        val restored = LocalLocatorFallback.entryFor(entry, null, sd)
        assertEquals(LocalPathResolution.Missing(sd), LocalPathIndex(listOf(indexed)).resolve(restored))
    }
}
