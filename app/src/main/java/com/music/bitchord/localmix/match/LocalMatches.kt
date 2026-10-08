package com.music.bitchord.localmix.match

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.download.Downloads
import com.music.bitchord.localmix.LocalMixBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.LocalRecording
import moe.rukamori.archivetune.localmix.equivalent.LocalEquivalentIndex
import moe.rukamori.archivetune.localmix.equivalent.LocalTrack
import moe.rukamori.archivetune.localmix.equivalent.MatchDoubt
import moe.rukamori.archivetune.localmix.equivalent.MatchReviewBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A device file that is confidently the same recording as a catalogue track. */
data class LocalHit(
    /** MediaStore content URI of the file. */
    val uri: String,
    /** Absolute file path, when MediaStore reported one. */
    val path: String?,
    /** Catalogue title and artist the match was made for (null for matches saved before they were kept). */
    val title: String? = null,
    val artist: String? = null,
    /** Reasons to double-check the match; null when unknown (saved before they were kept). */
    val doubts: Set<MatchDoubt>? = null,
)

/** One row of the match review screen. */
data class LocalMatchEntry(
    val videoId: String,
    val hit: LocalHit,
    /** "Artist - Title" of the local file, when the current library knows it. */
    val localLabel: String?,
    val confirmed: Boolean,
) {
    val uncertain: Boolean get() = !confirmed && !hit.doubts.isNullOrEmpty()
}

/**
 * LocalMix "prefer local": lets a catalogue (YouTube Music) track that is already
 * on the device as a plain MediaStore file behave as if it was downloaded in
 * the app - the downloaded badge shows and playback reads the file.
 *
 * Deliberately separate from [Downloads.saved]: that map is persisted and pruned
 * by its own rules, and a match is not a download.
 *
 * Matching is done only by [LocalEquivalentIndex] (version-aware, duration
 * within ~3 s, artist match). The track keeps its YouTube id as media id, so
 * queue, radio, likes and history are untouched; nothing here ever sends a
 * `content://` id anywhere.
 *
 * Usage:
 *  - [init] once from Application.onCreate.
 *  - [prime] with the songs of every catalogue page as it loads (off main).
 *  - [localUriFor] / [hitFor] when building a media item (synchronous, cheap).
 *  - [matched] for badges (videoId to uri).
 */
object LocalMatches {

    private const val TAG = "LocalMatches"
    private const val READABLE_TTL_MS = 30_000L
    private const val SIGNATURE_CHECK_INTERVAL_MS = 60_000L
    private const val SAVE_DEBOUNCE_MS = 2_000L
    private const val MAX_PENDING = 1_000
    private const val FILE_VERSION = 1
    private const val MAX_SHELF_LOOKUPS = 40

    @Volatile
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val buildMutex = Mutex()

    @Volatile
    private var index: LocalEquivalentIndex? = null

    /** content uri to path, for the library the current [index] was built from. */
    @Volatile
    private var pathsByUri: Map<String, String?> = emptyMap()

    /** Library signature the current [index] (and the negative cache) belongs to. */
    @Volatile
    private var signature: String? = null

    @Volatile
    private var lastSignatureCheck = 0L

    /**
     * Set by [load]: hits read from the file (perhaps restored from a backup of
     * another phone) are checked by path on the next library change.
     */
    @Volatile
    private var verifyRestoredPaths = false

    private val hits = ConcurrentHashMap<String, LocalHit>()

    /** videoIds checked against the current library and found to have no local copy. */
    private val misses = ConcurrentHashMap.newKeySet<String>()

    /** Songs primed before the index was ready; matched once it is. */
    private val pending = ConcurrentHashMap<String, Song>()

    /** The listener's verdicts: detached pairs (never matched again) and confirmed ones. Guarded by [reviewLock]. */
    private var review = MatchReviewBook()
    private val reviewLock = Any()

    /** content uri to "Artist - Title", for the review screen. */
    @Volatile
    private var labelsByUri: Map<String, String> = emptyMap()

    private val _revision = MutableStateFlow(0L)

    /** Bumped whenever a match, a verdict or the library changes; the review screen observes it. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Shelf cards whose duration was already fetched this session (or is being fetched). */
    private val shelfProbed = ConcurrentHashMap.newKeySet<String>()
    private val shelfMutex = Mutex()

    /** Shelf cards shown before the index was ready; looked up once it is. */
    @Volatile
    private var pendingShelf: List<ShelfItem> = emptyList()

    /** uri to (readable, checkedAtMs). */
    private val readable = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    private val _matched = MutableStateFlow<Map<String, String>>(emptyMap())

    /** videoId to local content uri, for every catalogue track with a confident local match. */
    val matched: StateFlow<Map<String, String>> = _matched.asStateFlow()

    private var saveJob: Job? = null

    /** True once the local library has been indexed at least once. */
    val isReady: Boolean get() = index != null

    /** Number of local tracks in the current index (0 before it is built). */
    val indexSize: Int get() = index?.size ?: 0

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        scope.launch {
            load()
            rebuild(force = true)
        }
    }

    /**
     * Rebuilds the index if the local library changed (or always with [force]).
     * Cheap when nothing changed: one MediaStore query of ids and dates.
     */
    fun refresh(force: Boolean = false) {
        if (appContext == null) return
        scope.launch { rebuild(force) }
    }

    /**
     * Computes local matches for [songs] in the background. Safe to call from any
     * thread with any list (local rows, videos and already known ids are
     * skipped cheaply). Call it whenever a page of catalogue songs is shown.
     */
    fun prime(songs: List<Song>) {
        if (appContext == null || songs.isEmpty()) return
        val todo = songs.filter {
            // A hit saved before titles and doubts were kept is matched again to fill them in.
            isCandidate(it) && hits[it.videoId].let { h -> h == null || h.doubts == null } && it.videoId !in misses
        }
        maybeCheckSignature()
        if (todo.isEmpty()) return
        scope.launch {
            val idx = index
            if (idx == null) {
                if (pending.size < MAX_PENDING) todo.forEach { pending.putIfAbsent(it.videoId, it) }
                return@launch
            }
            var changed = false
            for (song in todo) {
                if (compute(idx, song) != null) changed = true
            }
            if (changed) publish()
            scheduleSave()
        }
    }

    /**
     * Matches the track cards of home shelves, which carry no duration.
     *
     * Never matches without a duration: a card only passes a cheap filter
     * ([LocalEquivalentIndex.mayHave]: a local file with the same title by an
     * artist named in the card's subtitle), and only for those the track's
     * watch entry (with its duration and full credits) is fetched and matched
     * by the same strict rules as any other track. A few requests per home
     * load, none for cards with no local candidate.
     */
    fun primeShelfItems(items: List<ShelfItem>) {
        if (appContext == null || items.isEmpty()) return
        if (!AppSettings.preferLocalFiles.value) return
        scope.launch {
            shelfMutex.withLock {
                val idx = index ?: run {
                    pendingShelf = items
                    return@withLock
                }
                val todo = items.asSequence()
                    .filter { it.browseId == null }
                    .mapNotNull { item -> item.videoId?.takeIf { it.isNotBlank() }?.let { it to item } }
                    .filter { (id, _) ->
                        !hits.containsKey(id) && id !in misses && id !in shelfProbed &&
                            id !in Downloads.saved.value && !id.startsWith("content://") && !id.startsWith("file://")
                    }
                    .filter { (_, item) -> runCatching { idx.mayHave(item.title, item.subtitle) }.getOrDefault(false) }
                    .distinctBy { it.first }
                    .take(MAX_SHELF_LOOKUPS)
                    .toList()
                if (todo.isEmpty()) return@withLock
                var changed = false
                for ((id, _) in todo) {
                    val song = YtMusicRepository.trackLinks(id).getOrNull() ?: continue
                    shelfProbed.add(id)
                    // The watch entry must be this very track, with a duration.
                    if (song.videoId != id || song.durationText.isNullOrBlank() || !isCandidate(song)) continue
                    if (compute(idx, song) != null) changed = true
                }
                Log.d(TAG, "home shelves: ${todo.size} cards looked up, matched=$changed")
                if (changed) publish()
                scheduleSave()
            }
        }
    }

    /**
     * The local file to play for [song] instead of streaming it, or null.
     *
     * Synchronous: a map lookup, or an index lookup when the song has not been
     * primed yet. Returns null when the setting is off, the song is not a
     * catalogue track, there is no confident match, or the file is no longer
     * readable (then playback streams as usual).
     */
    fun hitFor(song: Song): LocalHit? {
        if (!AppSettings.preferLocalFiles.value) return null
        if (!isCandidate(song)) return null
        val id = song.videoId
        val hit = hits[id] ?: run {
            if (id in misses) return null
            val idx = index ?: return null
            val computed = compute(idx, song)
            if (computed != null) publish()
            scheduleSave()
            computed
        } ?: return null
        if (!isReadable(hit)) {
            drop(id)
            return null
        }
        return hit
    }

    /** As [hitFor], the content uri only. */
    fun localUriFor(song: Song): String? = hitFor(song)?.uri

    /**
     * Already known match for [videoId] (no matching, no metadata needed), checked
     * for readability. For callers that only have a media id.
     */
    fun cachedUriFor(videoId: String): String? {
        if (!AppSettings.preferLocalFiles.value) return null
        val hit = hits[videoId] ?: return null
        if (!isReadable(hit)) {
            drop(videoId)
            return null
        }
        return hit.uri
    }

    /**
     * The player failed to open the matched file for [videoId]: stop using it
     * (until the library changes) so the track streams.
     */
    fun forgetMissing(videoId: String) {
        if (videoId.isBlank() || videoId.startsWith("content://") || videoId.startsWith("file://")) return
        // Remembered as "no" even without a known hit: the failed item may come
        // from a restored queue, and an inline recompute must not pick it again.
        misses.add(videoId)
        val hit = hits.remove(videoId) ?: return
        Log.d(TAG, "$videoId: local file ${hit.uri} could not be opened; streaming instead")
        readable.remove(hit.uri)
        publish()
        scheduleSave()
        refresh()
    }

    /**
     * "Detach file": the listener says the matched file is not this track.
     * The pair is never matched again (it survives library changes); the track
     * streams. Returns false when there was no match to detach.
     */
    fun detach(videoId: String): Boolean {
        val hit = hits.remove(videoId) ?: return false
        synchronized(reviewLock) {
            review.reject(
                MatchReviewBook.Rejection(
                    catalogId = videoId,
                    file = MatchReviewBook.FileRef(hit.uri, hit.path),
                    title = hit.title,
                    artist = hit.artist,
                    at = System.currentTimeMillis(),
                ),
            )
        }
        misses.add(videoId)
        Log.d(TAG, "$videoId: detached from ${hit.uri}")
        publish()
        scheduleSave()
        return true
    }

    /** The listener checked the match of [videoId]; it is no longer shown as uncertain. */
    fun confirm(videoId: String) {
        val hit = hits[videoId] ?: return
        synchronized(reviewLock) { review.confirm(videoId, MatchReviewBook.FileRef(hit.uri, hit.path)) }
        bump()
        scheduleSave()
    }

    /** Undoes [detach]: the pair may match again the next time the track is shown. */
    fun restore(rejection: MatchReviewBook.Rejection) {
        synchronized(reviewLock) { review.restore(rejection.catalogId, rejection.file.uri, rejection.file.path) }
        misses.remove(rejection.catalogId)
        bump()
        scheduleSave()
    }

    /** Every current match, uncertain ones first, for the review screen. */
    fun entries(): List<LocalMatchEntry> {
        val labels = labelsByUri
        return hits.entries.map { (id, hit) ->
            LocalMatchEntry(
                videoId = id,
                hit = hit,
                localLabel = labels[hit.uri],
                confirmed = synchronized(reviewLock) { review.isConfirmed(id, hit.uri, hit.path) },
            )
        }.sortedWith(compareByDescending<LocalMatchEntry> { it.uncertain }.thenBy { it.hit.title ?: it.videoId })
    }

    /** Every detached pair, newest first. */
    fun rejections(): List<MatchReviewBook.Rejection> = synchronized(reviewLock) { review.rejections() }

    /** "Artist - Title" of a local file in the current library, when known. */
    fun localLabel(uri: String): String? = labelsByUri[uri]

    // ---- internals ---------------------------------------------------------

    private fun isCandidate(song: Song): Boolean {
        val id = song.videoId
        if (id.isBlank()) return false
        if (id.startsWith("content://") || id.startsWith("file://") || id.startsWith("/")) return false
        if (song.localUri != null) return false
        if (song.isVideo) return false
        if (SourceRegistry.parseTrackKey(id) != null) return false
        if (id in Downloads.saved.value) return false
        return true
    }

    private fun candidateOf(song: Song): CatalogCandidate {
        val artists = song.artists.map { it.name.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(song.artist).filter { it.isNotBlank() } }
        return CatalogCandidate(
            catalogId = song.videoId,
            title = song.title,
            artists = artists,
            album = song.albumName?.takeIf { it.isNotBlank() },
            durationSec = TrackMatcher.secondsOf(song.durationText),
        )
    }

    /** Matches one song, caching the answer. Never throws. */
    private fun compute(idx: LocalEquivalentIndex, song: Song): LocalHit? {
        val candidate = candidateOf(song)
        val found = runCatching { idx.findMatch(candidate) }
            .onFailure { Log.w(TAG, "match failed for ${song.videoId}: ${it.message}") }
            .getOrNull()
        if (found == null) {
            // Without a runtime the index cannot decide; do not remember that as "no".
            // An older hit being refreshed stays as it was.
            if (candidate.durationSec != null && !hits.containsKey(song.videoId)) misses.add(song.videoId)
            return null
        }
        val uri = found.track.songId
        val path = pathsByUri[uri]
        if (synchronized(reviewLock) { review.isRejected(song.videoId, uri, path) }) {
            misses.add(song.videoId)
            hits.remove(song.videoId)
            return null
        }
        val hit = LocalHit(
            uri = uri,
            path = path,
            title = song.title,
            artist = candidate.artists.joinToString(", ").ifBlank { null },
            doubts = found.doubts,
        )
        hits[song.videoId] = hit
        misses.remove(song.videoId)
        return hit
    }

    private fun drop(videoId: String) {
        if (hits.remove(videoId) != null) {
            publish()
            scheduleSave()
        }
    }

    private fun publish() {
        _matched.value = hits.mapValues { it.value.uri }
        bump()
    }

    private fun bump() {
        _revision.value = _revision.value + 1
    }

    private fun isReadable(hit: LocalHit): Boolean {
        val now = System.currentTimeMillis()
        readable[hit.uri]?.let { (ok, at) -> if (now - at < READABLE_TTL_MS) return ok }
        val ok = runCatching {
            val path = hit.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.exists()) return@runCatching f.canRead() || openable(hit.uri)
                // A path MediaStore reported that is gone: the file was deleted.
                return@runCatching false
            }
            openable(hit.uri)
        }.getOrDefault(false)
        readable[hit.uri] = ok to now
        return ok
    }

    private fun openable(uri: String): Boolean {
        val ctx = appContext ?: return false
        return runCatching {
            ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    private fun maybeCheckSignature() {
        val now = System.currentTimeMillis()
        if (now - lastSignatureCheck < SIGNATURE_CHECK_INTERVAL_MS) return
        lastSignatureCheck = now
        refresh()
    }

    /** count + max(DATE_MODIFIED) + max(_ID) + the settings that filter getLocalMusic. */
    private fun librarySignature(ctx: Context): String {
        if (!LocalMediaRepository.hasStoragePermission(ctx)) return "no-permission"
        var count = 0
        var maxModified = 0L
        var maxId = 0L
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATE_MODIFIED),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                count = c.count
                while (c.moveToNext()) {
                    maxId = maxOf(maxId, c.getLong(idCol))
                    maxModified = maxOf(maxModified, c.getLong(modCol))
                }
            }
        }.onFailure { Log.w(TAG, "signature query failed: ${it.message}") }
        return "$count:$maxModified:$maxId:${AppSettings.filterNonMusicAudio.value}:" +
            AppSettings.localMusicFolderUri.value
    }

    private suspend fun rebuild(force: Boolean) {
        val ctx = appContext ?: return
        buildMutex.withLock {
            lastSignatureCheck = System.currentTimeMillis()
            val sig = librarySignature(ctx)
            if (!force && index != null && sig == signature) return
            val songs = runCatching { LocalMediaRepository.getLocalMusic(ctx) }
                .onFailure { Log.w(TAG, "local library read failed: ${it.message}") }
                .getOrDefault(emptyList())
            val tracks = ArrayList<LocalTrack>(songs.size)
            val paths = HashMap<String, String?>(songs.size)
            val labels = HashMap<String, String>(songs.size)
            for (s in songs) {
                val uri = s.localUri ?: s.videoId.takeIf { it.startsWith("content://") } ?: continue
                paths[uri] = s.localPath
                labels[uri] = listOf(s.artist, s.title).filter { it.isNotBlank() }.joinToString(" - ")
                val artists = s.artist.takeUnless { it.isBlank() || it == "Unknown Artist" }
                    ?.let { listOf(it) } ?: emptyList()
                // durationText is whole seconds rounded down; centre it.
                val durationMs = TrackMatcher.secondsOf(s.durationText)?.let { it * 1000L + 500L }
                tracks += LocalTrack(
                    songId = uri,
                    recording = LocalRecording(
                        title = s.title,
                        artists = artists,
                        album = s.albumName,
                        durationMs = durationMs,
                    ),
                )
            }
            val built = runCatching { LocalEquivalentIndex(tracks) }
                .onFailure { Log.w(TAG, "index build failed: ${it.message}") }
                .getOrNull() ?: return
            val libraryChanged = sig != signature
            pathsByUri = paths
            labelsByUri = labels
            index = built
            signature = sig
            if (libraryChanged) {
                // New files can turn a "no" into a "yes"; removed files invalidate hits.
                misses.clear()
                shelfProbed.clear()
                hits.entries.removeIf { it.key in Downloads.saved.value || it.value.uri !in paths }
                if (verifyRestoredPaths) {
                    // Hits from a backup may come from another phone, where the
                    // same content uri is a different file.
                    hits.entries.removeIf { it.value.path != null && it.value.path != paths[it.value.uri] }
                }
                // A detached pair whose file moved to a new URI is still detached.
                synchronized(reviewLock) {
                    hits.entries.removeIf { review.isRejected(it.key, it.value.uri, paths[it.value.uri]) }
                }
                // Refresh paths of surviving hits.
                for ((id, hit) in hits.entries.toList()) {
                    val p = paths[hit.uri]
                    if (p != hit.path) hits[id] = hit.copy(path = p)
                }
                readable.clear()
            }
            verifyRestoredPaths = false
            Log.d(TAG, "index ready: ${built.size} local tracks, ${hits.size} matches")
            val queued = pending.values.toList()
            pending.clear()
            for (song in queued) {
                if (!hits.containsKey(song.videoId) && song.videoId !in misses) compute(built, song)
            }
            publish()
            scheduleSave()
        }
        val shelf = pendingShelf
        pendingShelf = emptyList()
        if (shelf.isNotEmpty()) primeShelfItems(shelf)
    }

    // ---- persistence (filesDir/localmix/matches.json) ----------------------

    private fun file(ctx: Context) = File(File(ctx.filesDir, "localmix"), "matches.json")

    private fun load() {
        val ctx = appContext ?: return
        val f = file(ctx)
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            if (root.optInt("version") != FILE_VERSION) return@runCatching
            signature = root.optString("signature").takeIf { it.isNotEmpty() }
            val h = root.optJSONObject("hits") ?: JSONObject()
            for (key in h.keys()) {
                val o = h.optJSONObject(key) ?: continue
                val uri = o.optString("u").takeIf { it.startsWith("content://") || it.startsWith("file://") } ?: continue
                hits[key] = LocalHit(
                    uri = uri,
                    path = o.optString("p").takeIf { it.isNotEmpty() },
                    title = o.optString("t").takeIf { it.isNotEmpty() },
                    artist = o.optString("a").takeIf { it.isNotEmpty() },
                    doubts = o.optJSONArray("d")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            runCatching { MatchDoubt.valueOf(arr.optString(i)) }.getOrNull()
                        }.toSet()
                    },
                )
            }
            val m = root.optJSONArray("misses") ?: JSONArray()
            for (i in 0 until m.length()) m.optString(i).takeIf { it.isNotEmpty() }?.let(misses::add)
            verifyRestoredPaths = true
            synchronized(reviewLock) { review = MatchReviewBook.fromJson(root.optJSONObject("review")) }
        }.onFailure { Log.w(TAG, "could not read ${f.path}: ${it.message}") }
        publish()
    }

    /** Keeps a delayed [save] from writing over a file that [restore] just put in place. */
    private val fileLock = Any()

    /** Writes the cache now instead of after the save delay, so a backup reads current data. */
    fun saveNow() {
        synchronized(this) {
            saveJob?.cancel()
            saveJob = null
        }
        save()
    }

    /**
     * Replaces `matches.json` with [raw] from a backup and reloads the cache from it,
     * then rebuilds the index as on startup: a signature from another device's
     * library makes [rebuild] drop the hits and misses that no longer apply.
     */
    suspend fun restore(target: File, raw: String) {
        synchronized(this) {
            saveJob?.cancel()
            saveJob = null
        }
        if (appContext == null) {
            synchronized(fileLock) { LocalMixBackup.writeAtomically(target, raw) }
            return
        }
        buildMutex.withLock {
            synchronized(fileLock) {
                LocalMixBackup.writeAtomically(target, raw)
                hits.clear()
                misses.clear()
                readable.clear()
                signature = null
                load()
            }
        }
        rebuild(force = true)
    }

    private fun scheduleSave() {
        if (appContext == null) return
        synchronized(this) {
            saveJob?.cancel()
            saveJob = scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                save()
            }
        }
    }

    private fun save() {
        val ctx = appContext ?: return
        val f = file(ctx)
        synchronized(fileLock) { saveLocked(f) }
    }

    private fun saveLocked(f: File) {
        runCatching {
            val root = JSONObject()
            root.put("version", FILE_VERSION)
            signature?.let { root.put("signature", it) }
            val h = JSONObject()
            for ((id, hit) in hits) {
                h.put(id, JSONObject().apply {
                    put("u", hit.uri)
                    hit.path?.let { put("p", it) }
                    hit.title?.let { put("t", it) }
                    hit.artist?.let { put("a", it) }
                    hit.doubts?.let { d -> put("d", JSONArray(d.map { it.name })) }
                })
            }
            root.put("hits", h)
            root.put("misses", JSONArray(misses.toList()))
            root.put("review", synchronized(reviewLock) { review.toJson() })
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) error("rename failed")
            }
        }.onFailure { Log.w(TAG, "could not write ${f.path}: ${it.message}") }
    }
}
