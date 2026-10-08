package com.music.bitchord.localmix

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.localmix.match.LocalMatches
import com.music.bitchord.localmix.radio.LocalRadioSeeds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The LocalMix part of [com.music.bitchord.data.stats.Backup]: the JSON files in
 * `filesDir/localmix/`, carried in the backup as parsed JSON under their file name.
 *
 * Only the names in [FILES] are ever read or written, so a backup cannot put a
 * file anywhere else. On restore a file that the backup has replaces the one on
 * the device; a file it does not have is left alone, and a backup without any
 * LocalMix section (an older one) changes nothing here.
 */
object LocalMixBackup {
    private const val TAG = "LocalMix"

    const val PLAYLISTS = "playlists.json"
    const val MATCHES = "matches.json"
    const val RADIO_LINKS = "radio_links.json"
    val FILES = listOf(PLAYLISTS, MATCHES, RADIO_LINKS)

    fun dir(context: Context): File = File(context.applicationContext.filesDir, "localmix")

    /** Every LocalMix file that exists and parses, by name. An unreadable one is skipped, not fatal. */
    fun export(context: Context): Map<String, JsonElement> {
        // matches.json is saved with a delay; write what is in memory first.
        LocalMatches.saveNow()
        val dir = dir(context)
        return FILES.mapNotNull { name ->
            val file = File(dir, name)
            if (!file.isFile) return@mapNotNull null
            runCatching { name to Json.parseToJsonElement(file.readText()) }
                .onFailure { Log.w(TAG, "Backup skips unreadable $name: ${it.message}") }
                .getOrNull()
        }.toMap()
    }

    /**
     * The part of [section] that [restore] would write. Throws when a known file
     * is not a JSON object, so a damaged backup is refused before anything is
     * written; names this build does not know are dropped.
     */
    fun validated(section: Map<String, JsonElement>): Map<String, String> =
        section.filterKeys { it in FILES }.mapValues { (name, element) ->
            require(element is JsonObject) { "The LocalMix file $name in that backup is damaged" }
            element.toString()
        }

    /** Writes [files] (from [validated]) and makes the in-memory caches read them again. */
    suspend fun restore(context: Context, files: Map<String, String>) {
        val dir = dir(context).apply { mkdirs() }
        files[PLAYLISTS]?.let { ImportedPlaylistStore.restore(File(dir, PLAYLISTS), it) }
        files[RADIO_LINKS]?.let { LocalRadioSeeds.restoreLinks(File(dir, RADIO_LINKS), it) }
        files[MATCHES]?.let { LocalMatches.restore(File(dir, MATCHES), it) }
    }

    /** Through a temp file and a rename, like the stores' own saves. */
    internal fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            // Some filesystems refuse to rename over an existing file.
            target.delete()
            check(temp.renameTo(target)) { "rename failed" }
        }
    }
}
