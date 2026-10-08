package moe.rukamori.archivetune.localmix.radio

import moe.rukamori.archivetune.localmix.TextNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * What is known about a device file when its radio link is looked up.
 *
 * [uri] is the MediaStore content URI (or file:// URI). It is NOT stable: a
 * MediaStore rescan can give the same file a new id. [relativePath] (folder +
 * file name, without the storage volume) and [sizeBytes] are, so together they
 * form [stableKey]. Title, artist and duration are the file's own tags; they
 * let a moved or renamed file find its link again.
 */
data class LocalFileIdentity(
    val uri: String,
    val relativePath: String?,
    val sizeBytes: Long?,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
) {
    /** "path:<relative path, lower case>|<size>", or null when either part is unknown. */
    val stableKey: String?
        get() {
            val path = relativePath?.let(LocalFileKeys::normalizePath)?.takeIf { it.isNotEmpty() } ?: return null
            val size = sizeBytes?.takeIf { it > 0 } ?: return null
            return "${LocalFileKeys.PATH_PREFIX}$path|$size"
        }

    /** The key a link for this file is saved under: [stableKey], or the URI when the path is unknown. */
    val storeKey: String get() = stableKey ?: uri
}

object LocalFileKeys {
    const val PATH_PREFIX = "path:"

    private val volumePrefix = Regex("^/?storage/(emulated/[0-9]+|[^/]+)/")

    /**
     * Path of the file relative to its storage volume: MediaStore's
     * RELATIVE_PATH + DISPLAY_NAME when known, else the absolute path with the
     * "/storage/<volume>/" part removed (the volume id of an SD card is not
     * part of the file's identity).
     */
    fun relativePath(relativeDir: String?, displayName: String?, absolutePath: String?): String? {
        if (!relativeDir.isNullOrBlank() && !displayName.isNullOrBlank()) {
            return normalizePath(relativeDir.trimEnd('/') + "/" + displayName)
        }
        val abs = absolutePath?.takeIf { it.isNotBlank() } ?: return null
        return normalizePath(abs.replace('\\', '/').replace(volumePrefix, ""))
    }

    internal fun normalizePath(path: String): String =
        path.replace('\\', '/').trim().trim('/').replace(Regex("/{2,}"), "/").lowercase()

    fun isStableKey(key: String): Boolean = key.startsWith(PATH_PREFIX)
}

/**
 * One remembered answer to "which YouTube Music recording is this local file?".
 *
 * [videoId] == null means the listener said that none of the offered recordings
 * is the right one. Every entry is valid only for the [revision] (file size and
 * modification time) it was made for; a changed file is matched again.
 *
 * [localKey] is [LocalFileIdentity.stableKey] for links saved since version 2
 * of the file, the content URI for older ones (until they are migrated).
 */
data class LocalRadioLink(
    val localKey: String,
    val revision: String,
    val videoId: String?,
    val manual: Boolean,
    /** Other catalog ids the matcher judged to be the same recording (AUTO only). */
    val equivalentIds: List<String> = emptyList(),
    /** Catalog title / artists of [videoId]. */
    val title: String? = null,
    val artists: String? = null,
    val checkedAt: Long = System.currentTimeMillis(),
    /** Last known content URI of the file. */
    val uri: String? = null,
    /** The file's own tags, for finding the link again after a move or rename. */
    val localTitle: String? = null,
    val localArtist: String? = null,
    val localDurationMs: Long? = null,
) {
    val isNoCorrectMatch: Boolean get() = videoId == null

    /** Copy describing [file] (key, URI and tags); answer and revision unchanged. */
    fun describing(file: LocalFileIdentity): LocalRadioLink = copy(
        localKey = file.storeKey,
        uri = file.uri,
        localTitle = file.title?.takeIf { it.isNotBlank() } ?: localTitle,
        localArtist = file.artist?.takeIf { it.isNotBlank() } ?: localArtist,
        localDurationMs = file.durationMs?.takeIf { it > 0 } ?: localDurationMs,
    )
}

/**
 * The JSON file `filesDir/localmix/radio_links.json`.
 *
 * Read once into memory; every change rewrites the file through a temporary
 * file and a rename, so a killed process never leaves half a file. Entries are
 * never dropped implicitly: only [remove] deletes one.
 *
 * Lookup ([lookup]) tries, in order:
 *  1. the stable key (relative path + size);
 *  2. the content URI, which is how version 1 entries were keyed (the entry
 *     is then re-keyed to the stable key and saved);
 *  3. the file's tags: exactly one entry with the same title and artist and the
 *     same size (or, when a size is unknown, a duration within 2 s). The entry
 *     is copied, not moved, under the new key: two copies of one song share
 *     the answer without taking it away from each other.
 */
class LocalRadioLinkStore(
    private val file: File,
    private val log: (String) -> Unit = {},
) {

    private val lock = Any()
    private var loaded = false
    private val links = LinkedHashMap<String, LocalRadioLink>()

    /** Remembered entry for [file], whatever revision it was made for. May migrate it. */
    fun lookup(file: LocalFileIdentity): LocalRadioLink? = synchronized(lock) {
        ensureLoaded()
        lookupLocked(file)
    }

    /**
     * Cheap peek by content URI only (no migration, no tag matching), for
     * synchronous UI guesses. Misses links whose file was re-indexed until the
     * next [lookup] records the new URI.
     */
    fun peekUri(uri: String): LocalRadioLink? = synchronized(lock) {
        ensureLoaded()
        links[uri] ?: links.values.lastOrNull { it.uri == uri }
    }

    /** The entry for [file], only when it was made for this [revision]. */
    fun find(file: LocalFileIdentity, revision: String): LocalRadioLink? =
        lookup(file)?.takeIf { it.revision == revision }

    /** Saves [link] under [file]'s key; a legacy entry for the same URI is replaced, not duplicated. */
    fun save(file: LocalFileIdentity, link: LocalRadioLink) {
        synchronized(lock) {
            ensureLoaded()
            val described = link.describing(file)
            if (described.localKey != file.uri) links.remove(file.uri)
            links[described.localKey] = described
            writeLocked()
        }
    }

    /** Every entry, in insertion order. */
    fun all(): List<LocalRadioLink> = synchronized(lock) {
        ensureLoaded()
        links.values.toList()
    }

    /** Deletes the entry saved under [localKey]; true when there was one. */
    fun remove(localKey: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val removed = links.remove(localKey) != null
        if (removed) writeLocked()
        removed
    }

    /**
     * Replaces the file with [raw] from a backup; it is read again on next use.
     * Version 1 entries in it are migrated by [lookup] and [migrateLegacy] as usual.
     */
    fun restore(raw: String) {
        synchronized(lock) {
            writeAtomically(raw)
            links.clear()
            loaded = false
        }
    }

    /**
     * Re-keys entries still keyed by a content URI (version 1) to their stable
     * key. [resolve] describes the file behind a URI, or returns null when that
     * URI no longer exists; such entries are kept as they are. Returns the
     * number of migrated entries.
     */
    fun migrateLegacy(resolve: (String) -> LocalFileIdentity?): Int = synchronized(lock) {
        ensureLoaded()
        val legacy = links.values.filter { !LocalFileKeys.isStableKey(it.localKey) }
        var migrated = 0
        for (link in legacy) {
            val uri = link.uri ?: link.localKey
            val identity = runCatching { resolve(uri) }.getOrNull() ?: continue
            if (identity.stableKey == null || !sameSize(link, identity)) continue
            if (rekeyLocked(link, identity)) migrated++
        }
        if (migrated > 0) writeLocked()
        migrated
    }

    private fun lookupLocked(file: LocalFileIdentity): LocalRadioLink? {
        val stable = file.stableKey
        if (stable != null) {
            links[stable]?.let { found ->
                if (found.uri != file.uri || found.localTitle == null) {
                    val updated = found.describing(file)
                    links[stable] = updated
                    writeLocked()
                    return updated
                }
                return found
            }
        }
        // A link made for this very URI: a version 1 entry, or a stable one whose file moved.
        val byUri = (links[file.uri] ?: links.values.firstOrNull { it.uri == file.uri && sameTags(it, file) })
            ?.takeIf { sameSize(it, file) }
        if (byUri != null) {
            if (stable != null && rekeyLocked(byUri, file)) writeLocked()
            return links[file.storeKey] ?: byUri
        }
        if (stable == null) return null
        val similar = links.values.filter { sameTags(it, file) }
        val only = similar.singleOrNull() ?: return null
        if (similar.any { it.videoId != only.videoId }) return null
        val copy = only.describing(file)
        links[copy.localKey] = copy
        writeLocked()
        log("radio link for ${file.uri} found by tags (was ${only.localKey})")
        return copy
    }

    /** Moves [link] to [file]'s stable key unless that key already holds an answer. */
    private fun rekeyLocked(link: LocalRadioLink, file: LocalFileIdentity): Boolean {
        val target = file.storeKey
        if (target == link.localKey) return false
        if (links.containsKey(target)) {
            // The stable key already has a (newer) answer; the old entry stays, harmless.
            return false
        }
        links.remove(link.localKey)
        links[target] = link.describing(file)
        return true
    }

    /**
     * False when the size the link was made for differs from the file's: after
     * a MediaStore rebuild an old content URI can name a different file.
     */
    private fun sameSize(link: LocalRadioLink, file: LocalFileIdentity): Boolean {
        val linkSize = link.revision.substringBefore(':').toLongOrNull() ?: return true
        val fileSize = file.sizeBytes ?: return true
        return linkSize == fileSize
    }

    /** Same title and artist, and the same size (or duration within 2 s when a size is unknown). */
    private fun sameTags(link: LocalRadioLink, file: LocalFileIdentity): Boolean {
        val lt = link.localTitle?.let(TextNormalizer::identityKey)?.takeIf { it.isNotEmpty() } ?: return false
        val ft = file.title?.let(TextNormalizer::identityKey)?.takeIf { it.isNotEmpty() } ?: return false
        if (lt != ft) return false
        val la = link.localArtist?.let(TextNormalizer::identityKey).orEmpty()
        val fa = file.artist?.let(TextNormalizer::identityKey).orEmpty()
        if (la != fa) return false
        val linkSize = link.localKey.takeIf(LocalFileKeys::isStableKey)?.substringAfterLast('|')?.toLongOrNull()
        val fileSize = file.sizeBytes?.takeIf { it > 0 }
        if (linkSize != null && fileSize != null) return linkSize == fileSize
        val ld = link.localDurationMs ?: return false
        val fd = file.durationMs ?: return false
        return abs(ld - fd) <= SAME_DURATION_MS
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        runCatching {
            if (!file.isFile) return
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val items = root.optJSONArray("links") ?: return
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                val key = o.optString("localKey").takeIf { it.isNotBlank() } ?: continue
                val revision = o.optString("revision").takeIf { it.isNotBlank() } ?: continue
                val equivalents = o.optJSONArray("equivalentIds")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                }.orEmpty()
                links[key] = LocalRadioLink(
                    localKey = key,
                    revision = revision,
                    videoId = o.optStringOrNull("videoId"),
                    manual = o.optBoolean("manual", false),
                    equivalentIds = equivalents,
                    title = o.optStringOrNull("title"),
                    artists = o.optStringOrNull("artists"),
                    checkedAt = o.optLong("checkedAt", 0L),
                    // Version 1 had no "uri": its key was the URI.
                    uri = o.optStringOrNull("uri") ?: key.takeUnless(LocalFileKeys::isStableKey),
                    localTitle = o.optStringOrNull("localTitle"),
                    localArtist = o.optStringOrNull("localArtist"),
                    localDurationMs = o.optLong("localDurationMs", 0L).takeIf { it > 0 },
                )
            }
        }.onFailure {
            log("radio links unreadable, starting empty: ${it.message}")
            // Never overwrite a file that could not be read: keep it aside.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".unreadable"), overwrite = true) }
        }
    }

    private fun writeLocked() {
        runCatching {
            val items = JSONArray()
            for (link in links.values) {
                items.put(
                    JSONObject().apply {
                        put("localKey", link.localKey)
                        put("revision", link.revision)
                        put("videoId", link.videoId ?: JSONObject.NULL)
                        put("manual", link.manual)
                        put("equivalentIds", JSONArray(link.equivalentIds))
                        put("title", link.title ?: JSONObject.NULL)
                        put("artists", link.artists ?: JSONObject.NULL)
                        put("checkedAt", link.checkedAt)
                        put("uri", link.uri ?: JSONObject.NULL)
                        put("localTitle", link.localTitle ?: JSONObject.NULL)
                        put("localArtist", link.localArtist ?: JSONObject.NULL)
                        link.localDurationMs?.let { put("localDurationMs", it) }
                    },
                )
            }
            val root = JSONObject().put("version", FILE_VERSION).put("links", items)
            writeAtomically(root.toString())
        }.onFailure { log("radio links not saved: ${it.message}") }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse to rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) error("rename failed")
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    companion object {
        const val FILE_VERSION = 2
        private const val SAME_DURATION_MS = 2_000L
    }
}
