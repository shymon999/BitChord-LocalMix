package moe.rukamori.archivetune.localmix.radio

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalRadioLinkStoreTest {
    private val dir: File = Files.createTempDirectory("lm-radio-links").toFile()
    private val json = File(dir, "radio_links.json")

    @After fun cleanup() {
        dir.deleteRecursively()
    }

    private fun store() = LocalRadioLinkStore(json)

    private fun file(
        uri: String = "content://media/external/audio/media/10",
        path: String? = "Music/RHCP/Californication.mp3",
        size: Long? = 7_000_000,
        title: String? = "Californication",
        artist: String? = "Red Hot Chili Peppers",
        durationMs: Long? = 329_000,
    ) = LocalFileIdentity(uri, path, size, title, artist, durationMs)

    private fun link(videoId: String? = "yt-cali", revision: String = "7000000:1700000000") =
        LocalRadioLink(localKey = "", revision = revision, videoId = videoId, manual = true, title = "Californication")

    private fun writeVersion1(vararg entries: Pair<String, String>) {
        val items = entries.joinToString(",") { (uri, video) ->
            """{"localKey":"$uri","revision":"7000000:1700000000","videoId":"$video","manual":true,
               "equivalentIds":["alt"],"title":"T","artists":"A","checkedAt":5}"""
        }
        json.writeText("""{"version":1,"links":[$items]}""")
    }

    @Test fun stableKeyUsesRelativePathAndSize() {
        assertEquals("path:music/rhcp/californication.mp3|7000000", file().stableKey)
        assertNull(file(path = null).stableKey)
        assertNull(file(size = null).stableKey)
        assertEquals("content://media/external/audio/media/10", file(path = null).storeKey)
    }

    @Test fun relativePathDropsStorageVolume() {
        assertEquals(
            "music/a/b.mp3",
            LocalFileKeys.relativePath(null, null, "/storage/emulated/0/Music/A/b.mp3"),
        )
        assertEquals(
            "music/a/b.mp3",
            LocalFileKeys.relativePath(null, null, "/storage/1234-ABCD/Music/A/b.mp3"),
        )
        assertEquals("music/a/b.mp3", LocalFileKeys.relativePath("Music/A/", "b.mp3", "/x/y"))
    }

    @Test fun linkSurvivesMediaStoreReindex() {
        store().save(file(), link())
        val reindexed = file(uri = "content://media/external/audio/media/999")
        val found = store().find(reindexed, "7000000:1700000000")
        assertEquals("yt-cali", found?.videoId)
        assertEquals("content://media/external/audio/media/999", store().lookup(reindexed)?.uri)
    }

    @Test fun changedRevisionIsNotReturnedByFind() {
        store().save(file(), link())
        assertNull(store().find(file(), "7000000:1800000000"))
        assertNotNull(store().lookup(file()))
    }

    @Test fun version1EntriesStillLoadAndMigrateOnLookup() {
        writeVersion1("content://media/external/audio/media/10" to "yt-old")
        val s = store()
        val found = s.find(file(), "7000000:1700000000")
        assertEquals("yt-old", found?.videoId)
        assertEquals(listOf("alt"), found?.equivalentIds)
        assertEquals(file().stableKey, found?.localKey)
        // Persisted under the stable key, the URI entry is gone, nothing duplicated.
        val all = store().all()
        assertEquals(1, all.size)
        assertEquals(file().stableKey, all.single().localKey)
        assertEquals(2, JSONObject(json.readText()).getInt("version"))
        // And it now survives a reindex.
        assertEquals("yt-old", store().lookup(file(uri = "content://media/external/audio/media/77"))?.videoId)
    }

    @Test fun eagerMigrationKeepsUnresolvableEntries() {
        writeVersion1(
            "content://media/external/audio/media/10" to "yt-a",
            "content://media/external/audio/media/11" to "yt-b",
        )
        val migrated = store().migrateLegacy { uri ->
            if (uri.endsWith("/10")) file() else null
        }
        assertEquals(1, migrated)
        val all = store().all().associateBy { it.videoId }
        assertEquals(file().stableKey, all.getValue("yt-a").localKey)
        assertEquals("Californication", all.getValue("yt-a").localTitle)
        assertEquals("content://media/external/audio/media/11", all.getValue("yt-b").localKey)
        assertEquals("content://media/external/audio/media/11", all.getValue("yt-b").uri)
    }

    @Test fun legacyUriNamingADifferentFileIsNotMigrated() {
        writeVersion1("content://media/external/audio/media/10" to "yt-old")
        val otherFile = file(path = "Music/Other/Song.mp3", size = 4_000_000, title = "Song", artist = "X")
        assertEquals(0, store().migrateLegacy { otherFile })
        assertNull(store().lookup(otherFile))
        assertEquals("content://media/external/audio/media/10", store().all().single().localKey)
    }

    @Test fun movedFileIsFoundByTagsAndSize() {
        store().save(file(), link())
        val moved = file(uri = "content://media/external/audio/media/50", path = "Music/Best/Californication.mp3")
        val found = store().lookup(moved)
        assertEquals("yt-cali", found?.videoId)
        // Copied, not moved: the original key still answers.
        assertEquals(2, store().all().size)
        assertNotNull(store().lookup(file()))
    }

    @Test fun tagFallbackNeedsSameSizeAndArtist() {
        store().save(file(), link())
        assertNull(store().lookup(file(uri = "content://x/2", path = "Music/B/c.mp3", size = 6_000_000)))
        assertNull(store().lookup(file(uri = "content://x/3", path = "Music/B/c.mp3", artist = "Cover Band")))
    }

    @Test fun tagFallbackRefusesConflictingAnswers() {
        store().save(file(), link("yt-1"))
        store().save(file(uri = "content://x/2", path = "Music/Copy/Californication.mp3"), link("yt-2"))
        assertNull(store().lookup(file(uri = "content://x/3", path = "Music/Third/Californication.mp3")))
    }

    @Test fun noneCorrectAnswerIsKeptToo() {
        store().save(file(), link(videoId = null))
        val found = store().lookup(file(uri = "content://media/external/audio/media/3"))
        assertNotNull(found)
        assertTrue(found!!.isNoCorrectMatch)
    }

    @Test fun removeDeletesOneEntry() {
        store().save(file(), link())
        val key = file().stableKey!!
        assertTrue(store().remove(key))
        assertFalse(store().remove(key))
        assertTrue(store().all().isEmpty())
    }

    @Test fun unreadableFileIsKeptAside() {
        json.writeText("{not json")
        val s = store()
        assertTrue(s.all().isEmpty())
        assertTrue(File(dir, "radio_links.json.unreadable").isFile)
    }

    @Test fun peekUriFindsLegacyAndMigratedEntries() {
        writeVersion1("content://media/external/audio/media/10" to "yt-old")
        assertEquals("yt-old", store().peekUri("content://media/external/audio/media/10")?.videoId)
        store().lookup(file())
        assertEquals("yt-old", store().peekUri("content://media/external/audio/media/10")?.videoId)
        assertNull(store().peekUri("content://media/external/audio/media/11"))
    }

    @Test fun restoreReplacesLoadedLinksAndKeepsOldBackupsReadable() {
        val s = store()
        s.save(file(), link(videoId = "yt-before"))
        assertEquals("yt-before", s.lookup(file())?.videoId)
        // An old backup still has version 1 entries keyed by content URI.
        s.restore(
            """{"version":1,"links":[{"localKey":"content://media/external/audio/media/10",
               "revision":"7000000:1700000000","videoId":"yt-backup","manual":true}]}""",
        )
        assertEquals("yt-backup", s.lookup(file())?.videoId)
        assertEquals("yt-backup", store().lookup(file())?.videoId)
        assertFalse(File(dir, "radio_links.json.tmp").exists())
    }
}
