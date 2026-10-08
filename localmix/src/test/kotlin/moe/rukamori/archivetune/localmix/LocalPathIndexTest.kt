/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class LocalPathIndexTest {
    private val a = IndexedLocalFile("content://media/external/audio/media/1", LocalFileLocator("external_primary", "Music/A", "a+b.mp3"))
    private val b = IndexedLocalFile("content://media/external/audio/media/2", LocalFileLocator("external_primary", "Music/B", "a+b.mp3"))
    private val index = LocalPathIndex(listOf(a, b))
    private fun entry(path: String) = M3uParser.parse(StringReader(path)).entries.single()
    private fun resolve(path: String) = index.resolve(entry(path))

    @Test fun primaryAliasesResolveSameActualAsset() {
        listOf("/sdcard", "/storage/emulated/0", "/storage/self/primary").forEach { root ->
            assertEquals(LocalPathResolution.Resolved(a), resolve("$root/Music/A/a+b.mp3"))
        }
    }

    @Test fun sameBasenameNeverOverridesWrongFolder() {
        assertTrue(resolve("/sdcard/Music/Absent/a+b.mp3") is LocalPathResolution.Missing)
        assertEquals(LocalPathResolution.Resolved(b), resolve("/sdcard/Music/B/a+b.mp3"))
    }

    @Test fun relativePathRequiresKnownParentAndAllowsSafeDotDot() {
        assertEquals(LocalPathResolution.ParentRequired, resolve("../A/a+b.mp3"))
        assertEquals(LocalPathResolution.Resolved(a), index.resolve(entry("../A/a+b.mp3"), PlaylistParent("external_primary", "Music/Playlists")))
        assertEquals(LocalPathResolution.InvalidPath, index.resolve(entry("../../a.mp3"), PlaylistParent("external_primary", "Music")))
    }

    @Test fun fileUriDecodesOnceWithoutChangingLiteralPlus() {
        assertEquals(LocalPathResolution.Resolved(a), resolve("file:///sdcard/Music/A/a+b.mp3"))
        assertEquals(LocalPathResolution.Resolved(a), resolve("file:///sdcard/Music/A/a%2Bb.mp3"))
        assertTrue(resolve("file:///sdcard/Music/A/a%252Bb.mp3") is LocalPathResolution.Missing)
        assertTrue(resolve("/sdcard/Music/A/a%2Bb.mp3") is LocalPathResolution.Missing)
    }

    @Test fun encodedUnicodeAndHashWorkAsFileUriPath() {
        val file = IndexedLocalFile("local", LocalFileLocator("external_primary", "Music", "Żółć #1.mp3"))
        val result = LocalPathIndex(listOf(file)).resolve(entry("file:///sdcard/Music/%C5%BB%C3%B3%C5%82%C4%87%20%231.mp3"))
        assertEquals(LocalPathResolution.Resolved(file), result)
    }

    @Test fun remoteOrMalformedFileUrisAreRejected() {
        assertEquals(LocalPathResolution.Unsupported, resolve("https://example.com/a.mp3"))
        assertEquals(LocalPathResolution.InvalidPath, resolve("file://server/sdcard/a.mp3"))
        assertEquals(LocalPathResolution.InvalidPath, resolve("file:///sdcard/a%QQ.mp3"))
        assertEquals(LocalPathResolution.InvalidPath, resolve("file:///sdcard/a.mp3#fragment"))
    }

    @Test fun exactCaseWinsButFoldedCollisionIsAmbiguous() {
        val first = IndexedLocalFile("one", LocalFileLocator("external_primary", "Music", "Song.mp3"))
        val second = IndexedLocalFile("two", LocalFileLocator("external_primary", "Music", "song.mp3"))
        val collision = LocalPathIndex(listOf(first, second))
        assertEquals(LocalPathResolution.Resolved(first), collision.resolve(entry("/sdcard/Music/Song.mp3")))
        val result = collision.resolve(entry("/sdcard/Music/SONG.mp3")) as LocalPathResolution.Ambiguous
        assertEquals(setOf("one", "two"), result.candidates.map { it.songId }.toSet())
    }

    @Test fun unicodeNormalizationNeverChoosesArbitrarily() {
        val first = IndexedLocalFile("one", LocalFileLocator("external_primary", "Music", "É.mp3"))
        val second = IndexedLocalFile("two", LocalFileLocator("external_primary", "Music", "E\u0301.mp3"))
        val collision = LocalPathIndex(listOf(first, second))
        assertTrue(collision.resolve(entry("/sdcard/Music/é.mp3")) is LocalPathResolution.Ambiguous)
    }

    @Test fun windowsAndUnknownRootsNeedExplicitMapping() {
        assertEquals(LocalPathResolution.RootMappingRequired, resolve("C:\\Music\\A\\a+b.mp3"))
        val mapping = LocalRootMapping("C:\\Music", PlaylistParent("external_primary", "Music"))
        assertEquals(LocalPathResolution.Resolved(a), index.resolve(entry("C:\\Music\\A\\a+b.mp3"), mappings = listOf(mapping)))
        assertEquals(LocalPathResolution.RootMappingRequired, resolve("/home/me/Music/A/a+b.mp3"))
    }

    @Test fun storageVolumeIsPartOfIdentity() {
        val card = IndexedLocalFile("card", LocalFileLocator("abcd-1234", "Music/A", "a+b.mp3"))
        val volumes = LocalPathIndex(listOf(a, card))
        assertEquals(LocalPathResolution.Resolved(card), volumes.resolve(entry("/storage/ABCD-1234/Music/A/a+b.mp3")))
        assertEquals(LocalPathResolution.Resolved(a), volumes.resolve(entry("/sdcard/Music/A/a+b.mp3")))
    }

    @Test fun contentUriOnlyResolvesKnownIndexedSong() {
        assertEquals(LocalPathResolution.Resolved(a), resolve(a.songId))
        assertEquals(LocalPathResolution.Unsupported, resolve("content://cloud.provider/audio/1"))
    }

    @Test fun primaryPrefixDoesNotMatchAnotherAndroidUser() {
        assertEquals(LocalPathResolution.RootMappingRequired, resolve("/storage/emulated/10/Music/A/a+b.mp3"))
        assertEquals(LocalPathResolution.RootMappingRequired, resolve("/sdcard-other/Music/A/a+b.mp3"))
    }

    @Test fun directoryRootCannotPretendToBeAnAudioFile() {
        assertEquals(LocalPathResolution.InvalidPath, resolve("/sdcard"))
    }

    @Test fun mappingCannotEscapeItsExplicitSourceRoot() {
        val mapping = LocalRootMapping("C:/Music", PlaylistParent("external_primary", "Music"))
        assertEquals(LocalPathResolution.InvalidPath, index.resolve(entry("C:/Music/../Other/a.mp3"), mappings = listOf(mapping)))
    }
}
