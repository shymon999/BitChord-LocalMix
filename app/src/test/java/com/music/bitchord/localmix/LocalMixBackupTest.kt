package com.music.bitchord.localmix

import com.music.bitchord.data.stats.Backup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The LocalMix section of the backup file, without touching the device. */
class LocalMixBackupTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a backup made before LocalMix still reads, with no LocalMix section`() {
        val old = """{"app":"bitchord","version":1,"versionName":"1.0","settings":{},"listening":[]}"""
        val file = json.decodeFromString(Backup.BackupFile.serializer(), old)
        assertNull(file.localmix)
    }

    @Test
    fun `the LocalMix section survives a round trip unchanged`() {
        val text = """{"app":"bitchord","version":1,"localmix":{
            "playlists.json":{"version":1,"playlists":[{"id":"a","name":"Club","entries":[{"ordinal":0,"rawPath":"x.mp3","durationSec":215.5}]}]},
            "radio_links.json":{"version":1,"links":[]}
        }}"""
        val file = json.decodeFromString(Backup.BackupFile.serializer(), text)
        val again = json.decodeFromString(
            Backup.BackupFile.serializer(),
            json.encodeToString(Backup.BackupFile.serializer(), file),
        )
        assertEquals(file.localmix, again.localmix)
        val files = LocalMixBackup.validated(again.localmix!!)
        assertEquals(setOf("playlists.json", "radio_links.json"), files.keys)
        assertEquals(file.localmix!!["playlists.json"], Json.parseToJsonElement(files.getValue("playlists.json")))
    }

    @Test
    fun `names outside the known files are never written`() {
        val section = mapOf(
            "../shared_prefs/x.xml" to Json.parseToJsonElement("{}"),
            "other.json" to Json.parseToJsonElement("{}"),
            "matches.json" to Json.parseToJsonElement("""{"version":1,"hits":{}}"""),
        )
        assertEquals(setOf("matches.json"), LocalMixBackup.validated(section).keys)
    }

    @Test
    fun `a damaged known file refuses the whole import`() {
        val section = mapOf("playlists.json" to JsonPrimitive("not an object"))
        assertThrows(IllegalArgumentException::class.java) { LocalMixBackup.validated(section) }
    }
}
