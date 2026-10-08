/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/**
 * Versioned snapshot of our own queue (IMPLEMENTATION_SPEC section 6). Hand written (de)serialisation, no reflection, so it
 * behaves the same in a minified build. Online-only queues keep the old upstream format and never go through this class.
 */
data class PersistedQueue(
    val revision: Long,
    val generation: Long,
    val entries: List<PersistedEntry>,
    val currentIndex: Int,
    val positionMs: Long,
    val modes: PlayerModes,
    val radio: PersistedRadio? = null,
    val returnContext: PersistedReturn? = null,
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** One logical queue slot. [playbackId] is what the player plays (local id or online id); [catalogId] is kept for radio. */
data class PersistedEntry(
    val occurrenceId: String,
    val playbackId: String,
    val playlistId: String? = null,
    val revision: Long? = null,
    val entryId: String? = null,
    val catalogId: String? = null,
    val sourceUri: String? = null,
    val mimeType: String? = null,
    val metadata: PersistedMetadata? = null,
)

/** Explicit metadata schema, independent of Java serialisation and Android parcelables. */
data class PersistedMetadata(
    val title: String,
    val artists: List<PersistedArtist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumTitle: String? = null,
    val setVideoId: String? = null,
    val spotifyTrackId: String? = null,
    val explicit: Boolean = false,
    val liked: Boolean = false,
    val likedDate: String? = null,
    val inLibrary: String? = null,
    val isMusicVideo: Boolean = false,
    val isPodcast: Boolean = false,
    val isrc: String? = null,
    val suggestedBy: String? = null,
)

data class PersistedArtist(val id: String?, val name: String, val thumbnailUrl: String? = null)

data class PersistedRadio(
    val sessionId: String,
    val seedCatalogIds: List<String>,
    val seenCatalogIds: List<String>,
    val continuation: String?,
    val ended: Boolean,
)

data class PersistedReturn(
    val playlistId: String?,
    val revision: Long?,
    val anchorOccurrenceId: String,
    val tail: List<PersistedEntry>,
    val modes: PlayerModes,
)

object PersistedQueueCodec {
    fun encode(q: PersistedQueue): String =
        JSONObject()
            .put("format", PersistedQueue.FORMAT_VERSION)
            .put("revision", q.revision)
            .put("generation", q.generation)
            .put("entries", JSONArray(q.entries.map(::entry)))
            .put("currentIndex", q.currentIndex)
            .put("positionMs", q.positionMs)
            .put("modes", modes(q.modes))
            .put("radio", q.radio?.let(::radio) ?: JSONObject.NULL)
            .put("return", q.returnContext?.let(::ret) ?: JSONObject.NULL)
            .toString()

    /** Returns null for anything that is not a complete version-1 snapshot; callers must not guess. */
    fun decode(text: String): PersistedQueue? =
        try {
            val o = JSONObject(text)
            if (o.getInt("format") != PersistedQueue.FORMAT_VERSION) {
                null
            } else {
                val entries = o.getJSONArray("entries").objects().map(::entry)
                val index = o.getInt("currentIndex")
                if (entries.isNotEmpty() && index !in entries.indices) {
                    null
                } else {
                    PersistedQueue(
                        revision = o.getLong("revision"),
                        generation = o.getLong("generation"),
                        entries = entries,
                        currentIndex = index,
                        positionMs = o.getLong("positionMs"),
                        modes = modes(o.getJSONObject("modes")),
                        radio = o.optJSONObject("radio")?.let(::radio),
                        returnContext = o.optJSONObject("return")?.let(::ret),
                    )
                }
            }
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun JSONObject.str(name: String): String? = if (isNull(name)) null else getString(name)

    private fun JSONObject.lng(name: String): Long? = if (isNull(name)) null else getLong(name)

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private fun entry(e: PersistedEntry) =
        JSONObject()
            .put("occurrenceId", e.occurrenceId)
            .put("playbackId", e.playbackId)
            .put("playlistId", e.playlistId ?: JSONObject.NULL)
            .put("revision", e.revision ?: JSONObject.NULL)
            .put("entryId", e.entryId ?: JSONObject.NULL)
            .put("catalogId", e.catalogId ?: JSONObject.NULL)
            .put("sourceUri", e.sourceUri ?: JSONObject.NULL)
            .put("mimeType", e.mimeType ?: JSONObject.NULL)
            .put("metadata", e.metadata?.let(::metadata) ?: JSONObject.NULL)

    private fun entry(o: JSONObject) =
        PersistedEntry(o.getString("occurrenceId"), o.getString("playbackId"), o.str("playlistId"), o.lng("revision"), o.str("entryId"), o.str("catalogId"), o.str("sourceUri"), o.str("mimeType"), o.optJSONObject("metadata")?.let(::metadata))

    private fun metadata(m: PersistedMetadata) = JSONObject()
        .put("title", m.title).put("duration", m.duration)
        .put("artists", JSONArray(m.artists.map { a -> JSONObject().put("id", a.id ?: JSONObject.NULL).put("name", a.name).put("thumbnailUrl", a.thumbnailUrl ?: JSONObject.NULL) }))
        .put("thumbnailUrl", m.thumbnailUrl ?: JSONObject.NULL)
        .put("albumId", m.albumId ?: JSONObject.NULL).put("albumTitle", m.albumTitle ?: JSONObject.NULL)
        .put("setVideoId", m.setVideoId ?: JSONObject.NULL).put("spotifyTrackId", m.spotifyTrackId ?: JSONObject.NULL)
        .put("explicit", m.explicit).put("liked", m.liked).put("likedDate", m.likedDate ?: JSONObject.NULL)
        .put("inLibrary", m.inLibrary ?: JSONObject.NULL).put("isMusicVideo", m.isMusicVideo).put("isPodcast", m.isPodcast)
        .put("isrc", m.isrc ?: JSONObject.NULL).put("suggestedBy", m.suggestedBy ?: JSONObject.NULL)

    private fun metadata(o: JSONObject) = PersistedMetadata(
        title = o.getString("title"), artists = o.getJSONArray("artists").objects().map { a -> PersistedArtist(a.str("id"), a.getString("name"), a.str("thumbnailUrl")) },
        duration = o.getInt("duration"), thumbnailUrl = o.str("thumbnailUrl"), albumId = o.str("albumId"), albumTitle = o.str("albumTitle"),
        setVideoId = o.str("setVideoId"), spotifyTrackId = o.str("spotifyTrackId"), explicit = o.getBoolean("explicit"), liked = o.getBoolean("liked"),
        likedDate = o.str("likedDate"), inLibrary = o.str("inLibrary"), isMusicVideo = o.getBoolean("isMusicVideo"), isPodcast = o.getBoolean("isPodcast"),
        isrc = o.str("isrc"), suggestedBy = o.str("suggestedBy"),
    )

    private fun modes(m: PlayerModes) = JSONObject().put("shuffle", m.shuffle).put("repeat", m.repeat.name)

    private fun modes(o: JSONObject) = PlayerModes(o.getBoolean("shuffle"), RepeatMode.valueOf(o.getString("repeat")))

    private fun radio(r: PersistedRadio) =
        JSONObject()
            .put("sessionId", r.sessionId)
            .put("seed", JSONArray(r.seedCatalogIds))
            .put("seen", JSONArray(r.seenCatalogIds))
            .put("continuation", r.continuation ?: JSONObject.NULL)
            .put("ended", r.ended)

    private fun radio(o: JSONObject) =
        PersistedRadio(o.getString("sessionId"), o.getJSONArray("seed").strings(), o.getJSONArray("seen").strings(), o.str("continuation"), o.getBoolean("ended"))

    private fun ret(r: PersistedReturn) =
        JSONObject()
            .put("playlistId", r.playlistId ?: JSONObject.NULL)
            .put("revision", r.revision ?: JSONObject.NULL)
            .put("anchor", r.anchorOccurrenceId)
            .put("tail", JSONArray(r.tail.map(::entry)))
            .put("modes", modes(r.modes))

    private fun ret(o: JSONObject) =
        PersistedReturn(o.str("playlistId"), o.lng("revision"), o.getString("anchor"), o.getJSONArray("tail").objects().map(::entry), modes(o.getJSONObject("modes")))
}

/** File operations the store uses; tests replace it to cut a write at any step. */
interface QueueFileOps {
    fun writeDurably(
        file: File,
        bytes: ByteArray,
    )

    fun moveAtomically(
        from: File,
        to: File,
    )

    fun delete(file: File)

    /** Publishes renames durably on filesystems exposing a directory descriptor. */
    fun syncDirectory(directory: File) = RealQueueFileOps.syncDirectory(directory)
}

object RealQueueFileOps : QueueFileOps {
    override fun writeDurably(
        file: File,
        bytes: ByteArray,
    ) {
        java.nio.channels.FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    }

    override fun moveAtomically(
        from: File,
        to: File,
    ) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(file: File) {
        Files.deleteIfExists(file.toPath())
    }

    override fun syncDirectory(directory: File) {
        // The desktop Windows JDK cannot open directories through FileChannel. Android/Linux can fsync the directory.
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        java.nio.channels.FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
}

/**
 * Directory layout: queue-<revision>.json (immutable), queue.pointer (names the current and the previous revision with their
 * SHA-256). A new snapshot is written and made durable first, only then the pointer moves; the previous snapshot stays until
 * the pointer has moved, so a crash at any step leaves a complete earlier queue. The pointer, not file times, decides what
 * restore loads, and a snapshot is accepted only if its checksum, format and revision match the pointer.
 */
class QueueSnapshotStore(
    private val dir: File,
    private val ops: QueueFileOps = RealQueueFileOps,
) {
    private val pointerFile get() = File(dir, POINTER)

    fun save(queue: PersistedQueue) {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create $dir" }
        val current = readPointer()
        val previous = current?.let { p ->
            if (read(p.revision, p.sha, p.generation) != null) {
                p.revision to p.sha
            } else if (p.previousRevision >= 0 && read(p.previousRevision, p.previousSha, null) != null) {
                p.previousRevision to p.previousSha
            } else {
                null
            }
        }
        require(previous == null || queue.revision > previous.first) { "Revision must grow: ${queue.revision} <= ${previous?.first}" }
        val bytes = PersistedQueueCodec.encode(queue).toByteArray(StandardCharsets.UTF_8)
        val temp = File(dir, "queue-${queue.revision}.json.tmp")
        val target = snapshotFile(queue.revision)
        ops.writeDurably(temp, bytes)
        ops.moveAtomically(temp, target)
        ops.syncDirectory(dir)
        val pointerText = "$POINTER_VERSION;${queue.revision};${queue.generation};${sha256(bytes)};${previous?.first ?: -1};${previous?.second ?: ""}"
        val pointerTemp = File(dir, "$POINTER.tmp")
        ops.writeDurably(pointerTemp, pointerText.toByteArray(StandardCharsets.UTF_8))
        ops.moveAtomically(pointerTemp, pointerFile)
        ops.syncDirectory(dir)
        // Everything except the new and the previous snapshot is obsolete now.
        dir.listFiles()
            ?.filter { it.name.startsWith("queue-") && (it.name.endsWith(".json") || it.name.endsWith(".tmp")) }
            ?.filter { it.name != target.name && it.name != previous?.let { p -> snapshotFile(p.first).name } }
            ?.forEach { runCatching { ops.delete(it) } }
    }

    /** The newest queue that the pointer vouches for, falling back to the previous one; null when neither is intact. */
    fun load(): PersistedQueue? {
        val p = readPointer() ?: return null
        return read(p.revision, p.sha, p.generation) ?: if (p.previousRevision >= 0) read(p.previousRevision, p.previousSha, null) else null
    }

    fun clear() {
        dir.listFiles()?.filter { it.name.startsWith("queue") }?.forEach { runCatching { ops.delete(it) } }
    }

    private fun read(
        revision: Long,
        sha: String,
        generation: Long?,
    ): PersistedQueue? {
        val file = snapshotFile(revision)
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        if (sha256(bytes) != sha) return null
        val queue = PersistedQueueCodec.decode(String(bytes, StandardCharsets.UTF_8)) ?: return null
        if (queue.revision != revision) return null
        if (generation != null && queue.generation != generation) return null
        return queue
    }

    private class Pointer(
        val revision: Long,
        val generation: Long,
        val sha: String,
        val previousRevision: Long,
        val previousSha: String,
    )

    private fun readPointer(): Pointer? {
        val text = try {
            pointerFile.readText(StandardCharsets.UTF_8)
        } catch (_: IOException) {
            return null
        }
        val f = text.split(';')
        if (f.size != 6 || f[0] != POINTER_VERSION.toString()) return null
        return try {
            Pointer(f[1].toLong(), f[2].toLong(), f[3], f[4].toLong(), f[5])
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun snapshotFile(revision: Long) = File(dir, "queue-$revision.json")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val POINTER = "queue.pointer"
        private const val POINTER_VERSION = 1
    }
}
