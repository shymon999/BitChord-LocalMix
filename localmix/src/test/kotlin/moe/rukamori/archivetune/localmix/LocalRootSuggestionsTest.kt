/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRootSuggestionsTest {
    @Test
    fun windowsAndUnixPathsGetTheirDeepestCommonFolderPerDrive() {
        val roots = LocalRootSuggestions.commonRoots(listOf(
            "C:\\Users\\Ola\\Music\\Club\\A\\01.mp3",
            "C:/Users/Ola/Music/Pop/B/02.flac",
            "D:/Archive/Live/03.mp3",
            "/home/deck/Music/Club/x.mp3",
            "/home/deck/Music/Rock/y.mp3",
            "relative/song.mp3",
            "content://media/external/audio/media/1",
        ))
        assertEquals(listOf("/home/deck/Music", "C:/Users/Ola/Music", "D:/Archive/Live"), roots)
    }

    @Test
    fun singleFileSuggestsItsFolderAndFileUrisAreDecoded() {
        assertEquals(listOf("/media/usb/Muzyka Taty"),
            LocalRootSuggestions.commonRoots(listOf("file:///media/usb/Muzyka%20Taty/a.mp3")))
    }

    @Test
    fun suggestedRootResolvesThroughConfirmedMapping() {
        val file = IndexedLocalFile("content://media/1", LocalFileLocator("external_primary", "Music/Club/A", "01.mp3"))
        val index = LocalPathIndex(listOf(file))
        val entry = M3uEntry(0, 1, "C:\\Users\\Ola\\Music\\Club\\A\\01.mp3", M3uLocationKind.LOCAL_PATH, null, null)
        assertEquals(LocalPathResolution.RootMappingRequired, index.resolve(entry))
        val root = LocalRootSuggestions.commonRoots(listOf(entry.location)).single()
        // A single file suggests its own folder; the user maps it to the matching phone folder.
        val mapping = LocalRootMapping(root, PlaylistParent("external_primary", "Music/Club/A"))
        assertEquals(LocalPathResolution.Resolved(file), index.resolve(entry, mappings = listOf(mapping)))
        val parentMapping = LocalRootMapping("C:/Users/Ola/Music", PlaylistParent("external_primary", "Music"))
        assertEquals(LocalPathResolution.Resolved(file), index.resolve(entry, mappings = listOf(parentMapping)))
    }

    @Test
    fun newerMappingForTheSameRootReplacesTheOlderOne() {
        val old = LocalRootMapping("C:/Music/", PlaylistParent("external_primary", "Old"))
        val other = LocalRootMapping("/home/a", PlaylistParent("external_primary", "A"))
        val new = LocalRootMapping("C:\\Music", PlaylistParent("external_primary", "New"))
        assertEquals(listOf(new, other), LocalRootSuggestions.merge(listOf(old, other), listOf(new)))
    }
}
