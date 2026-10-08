/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.*
import org.junit.Test

class LocalImportPreviewTest {
    private val file = IndexedLocalFile("local-a", LocalFileLocator("external_primary", "Music", "a.mp3"))
    private val index = LocalPathIndex(listOf(file))
    private fun preview(text: String) = LocalImportPreview.prepare(text.toByteArray(), "Fallback", index)

    @Test fun partialPreviewPreservesRepeatsAndUnresolvedPositions() {
        val result = preview("#PLAYLIST:Club\n/sdcard/Music/a.mp3\n/sdcard/Music/missing.mp3\n/sdcard/Music/a.mp3")
        assertEquals("Club", result.name)
        assertEquals(3, result.totalCount)
        assertEquals(2, result.resolvedCount)
        assertEquals(1, result.unavailableCount)
        assertTrue(result.requiresPartialImportDecision)
        assertEquals(listOf(0, 1, 2), result.entries.map { it.source.ordinal })
    }

    @Test fun sourceHashDetectsChangedInputAndDoesNotDependOnAvailability() {
        val text = "/sdcard/Music/a.mp3"
        val resolved = preview(text)
        val unavailable = LocalImportPreview.prepare(text.toByteArray(), "Fallback", LocalPathIndex(emptyList()))
        assertEquals(resolved.contentSha256, unavailable.contentSha256)
        assertEquals(64, resolved.contentSha256.length)
        assertNotEquals(resolved.contentSha256, preview("$text\n$text").contentSha256)
    }

    @Test fun remoteEntryNeedsExplicitPartialDecisionAndNeverResolvesAsAudio() {
        val result = preview("https://example.com/audio.mp3\n/sdcard/Music/a.mp3")
        assertTrue(result.requiresPartialImportDecision)
        assertEquals(LocalPathResolution.Unsupported, result.entries.first().resolution)
        assertEquals(2, result.totalCount)
    }

    @Test(expected = IllegalArgumentException::class) fun hlsCannotBeImportedAsLocalSongs() {
        preview("#EXT-X-TARGETDURATION:6\na.ts")
    }

    @Test(expected = IllegalArgumentException::class) fun emptyDocumentCannotReplaceAPlaylist() {
        preview("#EXTM3U\n#PLAYLIST:Empty")
    }

    @Test fun missingMetadataUsesFilenameProvidedByCaller() {
        assertEquals("Fallback", preview("/sdcard/Music/a.mp3").name)
    }
}
