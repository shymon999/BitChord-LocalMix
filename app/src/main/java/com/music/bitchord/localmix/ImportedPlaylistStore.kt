package com.music.bitchord.localmix

import android.content.Context
import com.music.bitchord.data.DebugLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One row of an imported M3U/M3U8 playlist, in the file's own order.
 *
 * Repeated rows stay separate entries with their own [ordinal]; a row whose
 * file could not be found keeps [resolvedLocalUri] null and is still listed,
 * so nothing the playlist names is silently dropped.
 */
data class ImportedPlaylistEntry(
    val ordinal: Int,
    /** The location exactly as the playlist wrote it. */
    val rawPath: String,
    /** EXTINF title, when the playlist had one. */
    val title: String? = null,
    /** EXTINF duration in seconds, when the playlist had a valid one. */
    val durationSec: Double? = null,
    /** MediaStore content URI of the matched track, or null when unavailable. */
    val resolvedLocalUri: String? = null,
    /** Volume-relative path of the matched track (`Music/Club/x.mp3`). */
    val resolvedPath: String? = null,
)

/** A confirmed or inferred source-root to device-folder mapping, see [moe.rukamori.archivetune.localmix.LocalRootMapping]. */
data class ImportedRootMapping(val sourceRoot: String, val volume: String, val relativePath: String)

data class ImportedPlaylist(
    val id: String,
    val name: String,
    /** The SAF document the playlist was read from. */
    val sourceUri: String,
    val importedAt: Long,
    /** Bumped on every reimport that changed anything. */
    val revision: Long,
    val entries: List<ImportedPlaylistEntry>,
    val contentSha256: String = "",
    /** Folder relative entries are resolved against, null when unknown. */
    val parentVolume: String? = null,
    val parentPath: String? = null,
    val rootMappings: List<ImportedRootMapping> = emptyList(),
    /** MediaStore state the resolution was made against, see [LocalMixLibrary.libraryStamp]. */
    val libraryStamp: String = "",
    /** Album art of the first available track, for the Library card and the page header. */
    val coverUrl: String? = null,
) {
    val browseId: String get() = ImportedPlaylistStore.BROWSE_PREFIX + id
    val availableCount: Int get() = entries.count { it.resolvedLocalUri != null }
    val missingCount: Int get() = entries.size - availableCount
}

/**
 * Imported local playlists, kept as one JSON file in `filesDir/localmix/playlists.json`.
 *
 * Written whole through a temp file and a rename, so a crash mid-write leaves
 * the previous version rather than half a file. All writes go through
 * [upsert] / [delete], which are synchronized; reads come from [playlists].
 */
object ImportedPlaylistStore {
    const val BROWSE_PREFIX = "localmix:playlist:"
    private const val TAG = "LocalMix"

    private var file: File? = null
    private val lock = Any()
    private val _playlists = MutableStateFlow<List<ImportedPlaylist>>(emptyList())
    val playlists: StateFlow<List<ImportedPlaylist>> = _playlists.asStateFlow()

    fun init(context: Context) {
        val dir = File(context.filesDir, "localmix").apply { mkdirs() }
        val target = File(dir, "playlists.json")
        synchronized(lock) {
            file = target
            _playlists.value = read(target)
        }
    }

    /** Replaces `playlists.json` with [raw] from a backup and shows its playlists. */
    fun restore(target: File, raw: String) = synchronized(lock) {
        LocalMixBackup.writeAtomically(target, raw)
        file = target
        _playlists.value = read(target)
    }

    private fun read(target: File): List<ImportedPlaylist> =
        runCatching { if (target.isFile) decode(target.readText()) else emptyList() }
            .onFailure { Log.w(TAG, "Unreadable imported playlists: ${it.message}") }
            .getOrDefault(emptyList())

    fun isBrowseId(browseId: String?): Boolean = browseId?.startsWith(BROWSE_PREFIX) == true

    fun get(idOrBrowseId: String): ImportedPlaylist? {
        val id = idOrBrowseId.removePrefix(BROWSE_PREFIX)
        return _playlists.value.firstOrNull { it.id == id }
    }

    fun findBySource(sourceUri: String): ImportedPlaylist? =
        _playlists.value.firstOrNull { it.sourceUri == sourceUri }

    /** Adds [playlist] or replaces the one with the same id, keeping its place in the list. */
    fun upsert(playlist: ImportedPlaylist) = synchronized(lock) {
        val current = _playlists.value
        val updated = if (current.any { it.id == playlist.id }) {
            current.map { if (it.id == playlist.id) playlist else it }
        } else {
            current + playlist
        }
        persist(updated)
    }

    fun delete(idOrBrowseId: String) = synchronized(lock) {
        val id = idOrBrowseId.removePrefix(BROWSE_PREFIX)
        persist(_playlists.value.filterNot { it.id == id })
    }

    private fun persist(list: List<ImportedPlaylist>) {
        _playlists.value = list
        val target = file ?: return
        runCatching {
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(encode(list))
            if (!temp.renameTo(target)) {
                // Some filesystems refuse to rename over an existing file.
                target.delete()
                check(temp.renameTo(target)) { "rename failed" }
            }
        }.onFailure { Log.w(TAG, "Failed saving imported playlists: ${it.message}") }
    }

    private fun encode(list: List<ImportedPlaylist>): String {
        val array = JSONArray()
        list.forEach { playlist ->
            val entries = JSONArray()
            playlist.entries.forEach { entry ->
                entries.put(
                    JSONObject()
                        .put("ordinal", entry.ordinal)
                        .put("rawPath", entry.rawPath)
                        .putOpt("title", entry.title)
                        .putOpt("durationSec", entry.durationSec)
                        .putOpt("resolvedLocalUri", entry.resolvedLocalUri)
                        .putOpt("resolvedPath", entry.resolvedPath),
                )
            }
            val mappings = JSONArray()
            playlist.rootMappings.forEach {
                mappings.put(
                    JSONObject().put("sourceRoot", it.sourceRoot).put("volume", it.volume)
                        .put("relativePath", it.relativePath),
                )
            }
            array.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("name", playlist.name)
                    .put("sourceUri", playlist.sourceUri)
                    .put("importedAt", playlist.importedAt)
                    .put("revision", playlist.revision)
                    .put("contentSha256", playlist.contentSha256)
                    .putOpt("parentVolume", playlist.parentVolume)
                    .putOpt("parentPath", playlist.parentPath)
                    .put("rootMappings", mappings)
                    .put("libraryStamp", playlist.libraryStamp)
                    .putOpt("coverUrl", playlist.coverUrl)
                    .put("entries", entries),
            )
        }
        return JSONObject().put("version", 1).put("playlists", array).toString()
    }

    private fun decode(raw: String): List<ImportedPlaylist> {
        val array = JSONObject(raw).optJSONArray("playlists") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching {
                val obj = array.getJSONObject(index)
                val entriesJson = obj.optJSONArray("entries") ?: JSONArray()
                val entries = (0 until entriesJson.length()).map { i ->
                    val e = entriesJson.getJSONObject(i)
                    ImportedPlaylistEntry(
                        ordinal = e.optInt("ordinal", i),
                        rawPath = e.getString("rawPath"),
                        title = e.optStringOrNull("title"),
                        durationSec = if (e.has("durationSec")) e.optDouble("durationSec").takeIf { it.isFinite() } else null,
                        resolvedLocalUri = e.optStringOrNull("resolvedLocalUri"),
                        resolvedPath = e.optStringOrNull("resolvedPath"),
                    )
                }.sortedBy { it.ordinal }
                val mappingsJson = obj.optJSONArray("rootMappings") ?: JSONArray()
                val mappings = (0 until mappingsJson.length()).map { i ->
                    val m = mappingsJson.getJSONObject(i)
                    ImportedRootMapping(m.getString("sourceRoot"), m.getString("volume"), m.optString("relativePath"))
                }
                ImportedPlaylist(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    sourceUri = obj.optString("sourceUri"),
                    importedAt = obj.optLong("importedAt"),
                    revision = obj.optLong("revision", 1),
                    entries = entries,
                    contentSha256 = obj.optString("contentSha256"),
                    parentVolume = obj.optStringOrNull("parentVolume"),
                    parentPath = obj.optStringOrNull("parentPath"),
                    rootMappings = mappings,
                    libraryStamp = obj.optString("libraryStamp"),
                    coverUrl = obj.optStringOrNull("coverUrl"),
                )
            }.onFailure { Log.w(TAG, "Skipping unreadable imported playlist: ${it.message}") }.getOrNull()
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
}
