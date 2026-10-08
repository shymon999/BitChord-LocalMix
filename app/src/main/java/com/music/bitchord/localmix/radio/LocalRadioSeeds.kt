package com.music.bitchord.localmix.radio

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.LocalRecording
import moe.rukamori.archivetune.localmix.MatchDecision
import moe.rukamori.archivetune.localmix.RecordingMatcher
import moe.rukamori.archivetune.localmix.radio.LocalFileIdentity
import moe.rukamori.archivetune.localmix.radio.LocalFileKeys
import moe.rukamori.archivetune.localmix.radio.LocalRadioLink
import moe.rukamori.archivetune.localmix.radio.LocalRadioLinkStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A YouTube Music recording offered as the radio seed of a local file. */
data class RadioSeedCandidate(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
    val equivalentIds: List<String> = emptyList(),
)

/** What the choice dialog is showing right now. */
data class LocalRadioChoiceRequest(
    val localTitle: String,
    val localArtist: String,
    val localAlbum: String?,
    val localDurationMs: Long?,
    val candidates: List<RadioSeedCandidate>,
    /** The listener said "none of these" for this file before. */
    val declinedBefore: Boolean,
    val initialQuery: String,
    internal val answer: CompletableDeferred<LocalRadioChoiceAnswer>,
)

sealed interface LocalRadioChoiceAnswer {
    data class Picked(val candidate: RadioSeedCandidate) : LocalRadioChoiceAnswer
    data object NoneCorrect : LocalRadioChoiceAnswer
    data object Cancelled : LocalRadioChoiceAnswer
}

/** Outcome of an explicit "Start radio" on a local file. */
sealed interface LocalSeedOutcome {
    data class Seed(val videoId: String) : LocalSeedOutcome
    /** "None of these" now or remembered from before. */
    data object NoneCorrect : LocalSeedOutcome
    data object Cancelled : LocalSeedOutcome
    /** Search failed; nothing was remembered. */
    data object NetworkError : LocalSeedOutcome
}

/**
 * Finds the YouTube Music recording that seeds a radio for a device file
 * (`Song.videoId` is a content:// or file:// URI) and remembers the answer.
 *
 * Matching itself is LocalMix's version-aware [RecordingMatcher]; this is only
 * the app side of it: MediaStore metadata in, YouTube Music SONG search out,
 * the JSON link file, and the choice dialog's state.
 *
 * Background callers ([backgroundSeed], [resolveSilently]) use remembered
 * answers or a confident automatic match and never ask. Only
 * [resolveInteractive], started by the listener's own tap, shows the dialog.
 */
object LocalRadioSeeds {

    private const val TAG = "LocalRadioSeeds"
    private const val MAX_CHOICES = 5
    private const val MAX_MANUAL_RESULTS = 10

    /** How long a file without a confident match is not searched again from the background. */
    private const val BACKGROUND_RETRY_MS = 30 * 60_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var store: LocalRadioLinkStore? = null

    private val matcher = RecordingMatcher()

    /** One interactive resolution at a time; a second tap waits for the first dialog. */
    private val interactive = Mutex()

    /** Store key + revision to when a background match was unsure or failed. In memory only. */
    private val backgroundMisses = ConcurrentHashMap<String, Long>()

    private val _choice = MutableStateFlow<LocalRadioChoiceRequest?>(null)

    /** The pending choice dialog, observed by MainActivity. */
    val choice: StateFlow<LocalRadioChoiceRequest?> = _choice.asStateFlow()

    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val s = LocalRadioLinkStore(File(File(app.filesDir, "localmix"), "radio_links.json")) { Log.w(TAG, it) }
        store = s
        // Links saved before the stable key existed are keyed by content URI;
        // re-key them while those URIs still name the same files.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val migrated = runCatching { s.migrateLegacy { uri -> identityOf(app, uri) } }.getOrDefault(0)
            if (migrated > 0) Log.d(TAG, "migrated $migrated radio links to stable keys")
        }
    }

    /** Every remembered link, for the review screen. Blocking (reads the file once); call off the main thread. */
    fun links(): List<LocalRadioLink> = store?.all().orEmpty()

    /** Forgets one remembered link; the file is matched (or asked about) again next time. */
    fun removeLink(localKey: String): Boolean {
        backgroundMisses.clear()
        return store?.remove(localKey) == true
    }

    /** Restores `radio_links.json` from a backup; written directly when not attached yet. */
    fun restoreLinks(target: File, raw: String) {
        val current = store
        if (current != null) current.restore(raw)
        else com.music.bitchord.localmix.LocalMixBackup.writeAtomically(target, raw)
    }

    /** A device file rather than a catalog track; downloads keep their real video id. */
    fun isLocalTrack(song: Song): Boolean =
        song.videoId.startsWith("content://") || song.videoId.startsWith("file://")

    private fun uriOf(song: Song): String = song.localUri?.takeIf { it.isNotBlank() } ?: song.videoId

    /**
     * Cheap, non-blocking guess for UI buttons: false only when the listener
     * already said that no catalog recording matches this file.
     */
    fun mayHaveSeed(song: Song): Boolean {
        if (!isLocalTrack(song)) return true
        val link = store?.peekUri(uriOf(song)) ?: return true
        return !link.isNoCorrectMatch
    }

    /** Remembered seed id and its equivalents, for a still-unchanged file. Never searches or asks. */
    suspend fun cachedSeed(song: Song): Pair<String, Set<String>>? {
        val s = store ?: return null
        val local = describe(song) ?: return null
        val link = s.findIo(local.file, local.revision) ?: return null
        val id = link.videoId ?: return null
        return id to (link.equivalentIds.toSet() + id)
    }

    /**
     * Resolves the seed for an explicit radio start: remembered link, automatic
     * match, or the listener's choice. Suspends while the dialog is open.
     */
    suspend fun resolveInteractive(song: Song): LocalSeedOutcome = interactive.withLock {
        val s = store ?: return LocalSeedOutcome.NetworkError
        val local = describe(song) ?: return LocalSeedOutcome.NetworkError
        val remembered = s.findIo(local.file, local.revision)
        remembered?.videoId?.let { return LocalSeedOutcome.Seed(it) }

        val declinedBefore = remembered?.isNoCorrectMatch == true
        val offered: List<RadioSeedCandidate> = if (declinedBefore) {
            // The listener already rejected what search offers; open straight
            // to the manual search instead of repeating the same list.
            emptyList()
        } else {
            when (val m = autoMatch(s, local)) {
                is AutoMatch.Matched -> return LocalSeedOutcome.Seed(m.videoId)
                is AutoMatch.Unsure -> m.offered
                AutoMatch.Failed -> return LocalSeedOutcome.NetworkError
            }
        }

        val answer = CompletableDeferred<LocalRadioChoiceAnswer>()
        val request = LocalRadioChoiceRequest(
            localTitle = song.title,
            localArtist = song.artist,
            localAlbum = song.albumName,
            localDurationMs = local.recording.durationMs,
            candidates = offered,
            declinedBefore = declinedBefore,
            initialQuery = queriesFor(local.recording).firstOrNull().orEmpty(),
            answer = answer,
        )
        _choice.value = request
        val result = try {
            answer.await()
        } finally {
            _choice.compareAndSet(request, null)
        }
        when (result) {
            is LocalRadioChoiceAnswer.Picked -> {
                // A manual pick does not establish aliases; only the picked id is its own seed.
                s.saveIo(local.file, local.linkTo(result.candidate.videoId, manual = true, emptyList(), result.candidate))
                LocalSeedOutcome.Seed(result.candidate.videoId)
            }
            LocalRadioChoiceAnswer.NoneCorrect -> {
                s.saveIo(local.file, local.linkTo(null, manual = true, emptyList(), null))
                LocalSeedOutcome.NoneCorrect
            }
            LocalRadioChoiceAnswer.Cancelled -> LocalSeedOutcome.Cancelled
        }
    }

    /**
     * Seed for a radio started without a screen (media session command):
     * remembered link or a confident automatic match. Never asks; returns null
     * when only the listener could decide, or when the search failed.
     */
    suspend fun resolveSilently(song: Song): String? {
        val s = store ?: return null
        val local = describe(song) ?: return null
        val remembered = s.findIo(local.file, local.revision)
        if (remembered != null) return remembered.videoId
        return (autoMatch(s, local) as? AutoMatch.Matched)?.videoId
    }

    /**
     * Seed for AutoPlay running in the background (no screen to ask on):
     * the remembered link, or else a confident automatic match, which is then
     * remembered like any AUTO match. Never guesses and never asks: an unsure
     * or failed search gives no seed, and is not repeated for
     * [BACKGROUND_RETRY_MS]. A remembered "none of these" stays no seed.
     * Returns the seed and every id known to be the same recording.
     */
    suspend fun backgroundSeed(song: Song): Pair<String, Set<String>>? {
        val s = store ?: return null
        val local = describe(song) ?: return null
        val remembered = s.findIo(local.file, local.revision)
        if (remembered != null) {
            val id = remembered.videoId ?: return null
            return id to (remembered.equivalentIds.toSet() + id)
        }
        val key = "${local.file.storeKey}#${local.revision}"
        val now = System.currentTimeMillis()
        backgroundMisses[key]?.let { if (now - it < BACKGROUND_RETRY_MS) return null }
        return when (val m = autoMatch(s, local)) {
            is AutoMatch.Matched -> {
                backgroundMisses.remove(key)
                m.videoId to (m.equivalentIds.toSet() + m.videoId)
            }
            else -> {
                backgroundMisses[key] = now
                null
            }
        }
    }

    private sealed interface AutoMatch {
        data class Matched(val videoId: String, val equivalentIds: List<String> = emptyList()) : AutoMatch
        data class Unsure(val offered: List<RadioSeedCandidate>) : AutoMatch
        data object Failed : AutoMatch
    }

    /** Up to two SONG searches and the matcher; a confident match is remembered as AUTO. */
    private suspend fun autoMatch(s: LocalRadioLinkStore, local: LocalInfo): AutoMatch {
        val found = mutableListOf<RadioSeedCandidate>()
        var decision: MatchDecision? = null
        for (query in queriesFor(local.recording)) {
            // A network failure is not an answer: nothing is remembered.
            val page = searchSongs(query).getOrElse { return AutoMatch.Failed }
            found += page
            decision = matcher.decide(local.recording, found.distinctBy { it.videoId }.map { it.asCatalog() })
            if (decision is MatchDecision.Auto) break
        }
        val unique = found.distinctBy { it.videoId }
        val byId = unique.associateBy { it.videoId }
        return when (val d = decision) {
            is MatchDecision.Auto -> {
                val chosen = byId[d.chosen.candidate.catalogId]
                    ?: return AutoMatch.Unsure(rank(local.recording, unique).take(MAX_CHOICES))
                s.saveIo(local.file, local.linkTo(chosen.videoId, manual = false, d.chosen.equivalentIds.distinct(), chosen))
                AutoMatch.Matched(chosen.videoId, d.chosen.equivalentIds.distinct().filter { it != chosen.videoId })
            }
            is MatchDecision.NeedsChoice -> AutoMatch.Unsure(
                d.candidates.mapNotNull { scored ->
                    byId[scored.candidate.catalogId]?.copy(equivalentIds = scored.equivalentIds)
                },
            )
            // Nothing judged close enough: still show the best raw results,
            // the listener may recognise the right one where the matcher did not.
            is MatchDecision.NotFound, null -> AutoMatch.Unsure(rank(local.recording, unique).take(MAX_CHOICES))
        }
    }

    private suspend fun LocalRadioLinkStore.findIo(file: LocalFileIdentity, revision: String): LocalRadioLink? =
        withContext(Dispatchers.IO) { find(file, revision) }

    private suspend fun LocalRadioLinkStore.saveIo(file: LocalFileIdentity, link: LocalRadioLink) =
        withContext(Dispatchers.IO) { save(file, link) }

    /** Called by the dialog. */
    fun answer(request: LocalRadioChoiceRequest, answer: LocalRadioChoiceAnswer) {
        request.answer.complete(answer)
    }

    /** The dialog's manual search, ranked against the local file. */
    suspend fun manualSearch(request: LocalRadioChoiceRequest, query: String): Result<List<RadioSeedCandidate>> {
        val recording = LocalRecording(
            title = request.localTitle,
            artists = artistsOf(request.localArtist),
            album = request.localAlbum,
            durationMs = request.localDurationMs,
        )
        return searchSongs(query).map { rank(recording, it).take(MAX_MANUAL_RESULTS) }
    }

    private fun rank(recording: LocalRecording, found: List<RadioSeedCandidate>): List<RadioSeedCandidate> {
        if (found.isEmpty()) return emptyList()
        val byId = found.associateBy { it.videoId }
        return matcher.rank(recording, found.map { it.asCatalog() }).mapNotNull { byId[it.candidate.catalogId] }
    }

    private suspend fun searchSongs(query: String): Result<List<RadioSeedCandidate>> =
        YtMusicRepository.search(query, SearchFilter.SONGS).map { rows ->
            rows.mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    else -> null
                }
            }
                .filterNot { it.isVideo || isLocalTrack(it) || it.videoId.isBlank() }
                .distinctBy { it.videoId }
                .map { it.asCandidate() }
        }

    private fun Song.asCandidate() = RadioSeedCandidate(
        videoId = videoId,
        title = title,
        artists = artists.map { it.name }.filter { it.isNotBlank() }.ifEmpty { artistsOf(artist) },
        album = albumName,
        durationSec = parseDurationSeconds(durationText),
        thumbnailUrl = thumbnailUrl,
    )

    private fun RadioSeedCandidate.asCatalog() = CatalogCandidate(
        catalogId = videoId,
        title = title,
        artists = artists,
        album = album,
        durationSec = durationSec,
    )

    /** "artist title" first, then the bare title. */
    private fun queriesFor(r: LocalRecording): List<String> {
        val main = r.artists.firstOrNull()?.split(";", ", ")?.firstOrNull()?.trim().orEmpty()
        return listOf("$main ${r.title}".trim(), r.title.trim())
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun artistsOf(artist: String): List<String> =
        artist.takeUnless { it.isBlank() || it.equals("Unknown Artist", ignoreCase = true) || it == "<unknown>" }
            ?.let(::listOf)
            .orEmpty()

    private class LocalInfo(val file: LocalFileIdentity, val revision: String, val recording: LocalRecording) {
        fun linkTo(videoId: String?, manual: Boolean, equivalents: List<String>, candidate: RadioSeedCandidate?) =
            LocalRadioLink(
                localKey = file.storeKey,
                revision = revision,
                videoId = videoId,
                manual = manual,
                equivalentIds = equivalents.filter { it.isNotBlank() && it != videoId },
                title = candidate?.title,
                artists = candidate?.artists?.joinToString(", "),
            )
    }

    /** File identity, revision (size + modification time) and matcher input for a device file. */
    private suspend fun describe(song: Song): LocalInfo? = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext null
        val stat = statOf(context, uriOf(song))
        val modified = stat.modifiedSeconds ?: song.localDateModifiedSeconds
        val duration = stat.durationMs ?: parseDurationSeconds(song.durationText)?.let { it * 1000L }
        val artists = song.artists.map { it.name }.filter { it.isNotBlank() }.ifEmpty { artistsOf(song.artist) }
        LocalInfo(
            file = LocalFileIdentity(
                uri = uriOf(song),
                relativePath = stat.relativePath
                    ?: LocalFileKeys.relativePath(null, null, song.localPath),
                sizeBytes = stat.size,
                // MediaStore's own tags, the same ones migration reads.
                title = stat.title ?: song.title,
                artist = stat.artist ?: artists.firstOrNull(),
                durationMs = duration,
            ),
            revision = "${stat.size ?: "?"}:${modified ?: "?"}",
            recording = LocalRecording(
                title = song.title,
                artists = artists,
                album = song.albumName?.takeIf { it.isNotBlank() },
                durationMs = duration,
            ),
        )
    }

    private class FileStat(
        val relativePath: String? = null,
        val size: Long? = null,
        val modifiedSeconds: Long? = null,
        val durationMs: Long? = null,
        val title: String? = null,
        val artist: String? = null,
        val exists: Boolean = false,
    )

    /** MediaStore (or file system) facts about [uriString]; empty when it cannot be read. */
    private fun statOf(context: Context, uriString: String): FileStat = runCatching {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "content" -> context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.RELATIVE_PATH,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                ),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun str(i: Int) = if (c.isNull(i)) null else c.getString(i)
                fun long(i: Int) = if (c.isNull(i)) null else c.getLong(i)
                FileStat(
                    relativePath = LocalFileKeys.relativePath(str(3), str(4), str(5)),
                    size = long(0)?.takeIf { it > 0 },
                    modifiedSeconds = long(1),
                    durationMs = long(2)?.takeIf { it > 0 },
                    title = str(6),
                    artist = str(7)?.takeUnless { it == "<unknown>" },
                    exists = true,
                )
            }
            "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }?.let {
                FileStat(
                    relativePath = LocalFileKeys.relativePath(null, null, it.absolutePath),
                    size = it.length(),
                    modifiedSeconds = it.lastModified() / 1000,
                    exists = true,
                )
            }
            else -> null
        }
    }.getOrNull() ?: FileStat()

    /** Identity of the file behind [uri] now, or null when the URI names nothing. For migration. */
    private fun identityOf(context: Context, uri: String): LocalFileIdentity? {
        val stat = statOf(context, uri).takeIf { it.exists } ?: return null
        return LocalFileIdentity(
            uri = uri,
            relativePath = stat.relativePath,
            sizeBytes = stat.size,
            title = stat.title,
            artist = stat.artist,
            durationMs = stat.durationMs,
        )
    }

    /** "3:45" or "1:02:03" to seconds. */
    internal fun parseDurationSeconds(text: String?): Int? {
        val parts = text?.trim()?.split(':')?.takeIf { it.size in 2..3 } ?: return null
        var total = 0
        for (p in parts) total = total * 60 + (p.trim().toIntOrNull() ?: return null)
        return total.takeIf { it > 0 }
    }
}
