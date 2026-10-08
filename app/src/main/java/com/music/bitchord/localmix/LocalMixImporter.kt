package com.music.bitchord.localmix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.localmix.IndexedLocalFile
import moe.rukamori.archivetune.localmix.LocalImportPreview
import moe.rukamori.archivetune.localmix.LocalPathResolution
import moe.rukamori.archivetune.localmix.LocalRootMapping
import moe.rukamori.archivetune.localmix.LocalRootSuggestions
import moe.rukamori.archivetune.localmix.M3uEntry
import moe.rukamori.archivetune.localmix.M3uLocationKind
import moe.rukamori.archivetune.localmix.M3uParser
import moe.rukamori.archivetune.localmix.PlaylistParent
import moe.rukamori.archivetune.localmix.PlaylistSource
import moe.rukamori.archivetune.localmix.PlaylistSourceMatcher
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** What one import run did: the playlists written, and the documents that could not be read, by name. */
data class LocalMixImportResult(
    val imported: List<ImportedPlaylist>,
    val failed: List<Pair<String, String>>,
)

/**
 * Turns M3U/M3U8 documents picked through SAF into [ImportedPlaylist]s.
 *
 * Parsing, hashing and path resolution are the domain module's
 * ([M3uParser], [LocalImportPreview], [moe.rukamori.archivetune.localmix.LocalPathIndex]);
 * this only supplies what Android knows: the document bytes, the folder the
 * document sits in, and the MediaStore path snapshot. When the folder is
 * unknown (a document from a provider that does not expose paths) or the
 * playlist was written on another device (`C:/Users/...`), the folder or root
 * is inferred by voting on files whose path ends with the playlist's own
 * relative path, never by matching a bare file name, and the domain resolver
 * then does the actual resolution.
 */
object LocalMixImporter {
    private const val TAG = "LocalMix"
    private const val UNKNOWN_SEGMENT = "_localmix_"
    private const val VOTE_SAMPLE = 80

    private val playlistExtensions = setOf("m3u", "m3u8")

    fun isPlaylistName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in playlistExtensions

    /** Keeps read access to picked documents and folders across restarts, so playlists can be reimported. */
    fun persistPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "No persistable permission for $uri: ${it.message}") }
    }

    suspend fun importDocuments(context: Context, uris: List<Uri>): LocalMixImportResult = withContext(Dispatchers.IO) {
        uris.forEach { persistPermission(context, it) }
        requireLibraryAccess(context)
        val snapshot = LocalMixLibrary.snapshot(context)
        val imported = mutableListOf<ImportedPlaylist>()
        val failed = mutableListOf<Pair<String, String>>()
        uris.distinct().forEach { uri ->
            coroutineContext.ensureActive()
            val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist.m3u8"
            if (!isPlaylistName(name)) {
                failed += name to "not an .m3u/.m3u8 file"
                return@forEach
            }
            runCatching { importOne(context, uri, name, documentParent(uri), snapshot) }
                .onSuccess { imported += it }
                .onFailure { failed += name to (it.message ?: it.javaClass.simpleName) }
        }
        LocalMixImportResult(imported, failed)
    }

    /** Imports every `.m3u` / `.m3u8` under [tree], up to a few folders deep. */
    suspend fun importTree(context: Context, tree: Uri): LocalMixImportResult = withContext(Dispatchers.IO) {
        persistPermission(context, tree)
        requireLibraryAccess(context)
        val documents = runCatching { listPlaylistDocuments(context, tree) }
            .getOrElse { return@withContext LocalMixImportResult(emptyList(), listOf(tree.toString() to (it.message ?: ""))) }
        val snapshot = LocalMixLibrary.snapshot(context)
        val imported = mutableListOf<ImportedPlaylist>()
        val failed = mutableListOf<Pair<String, String>>()
        documents.forEach { (uri, name) ->
            coroutineContext.ensureActive()
            runCatching { importOne(context, uri, name, documentParent(uri), snapshot) }
                .onSuccess { imported += it }
                .onFailure { failed += name to (it.message ?: it.javaClass.simpleName) }
        }
        LocalMixImportResult(imported, failed)
    }

    /** Reads [playlist]'s source document again and updates it in place. */
    suspend fun reimport(context: Context, playlist: ImportedPlaylist): ImportedPlaylist = withContext(Dispatchers.IO) {
        requireLibraryAccess(context)
        val uri = Uri.parse(playlist.sourceUri)
        val name = displayName(context, uri) ?: (playlist.name + ".m3u8")
        val parent = documentParent(uri)
            ?: playlist.parentVolume?.let { PlaylistParent(it, playlist.parentPath.orEmpty()) }
        importOne(context, uri, name, parent, LocalMixLibrary.snapshot(context), target = playlist)
    }

    /**
     * Without the audio permission every row would be stored as missing, so
     * an import is refused instead; the caller reports the message.
     */
    private fun requireLibraryAccess(context: Context) {
        check(LocalMediaRepository.hasStoragePermission(context)) {
            context.getString(com.music.bitchord.R.string.storage_required_read)
        }
    }

    private suspend fun importOne(
        context: Context,
        uri: Uri,
        displayName: String,
        knownParent: PlaylistParent?,
        snapshot: LocalMixSnapshot,
        target: ImportedPlaylist? = null,
    ): ImportedPlaylist {
        val raw = readDocument(context, uri)
        // The domain parser reads strict UTF-8. Playlists written by Windows
        // players are often in the system code page instead, so those are
        // re-encoded first rather than rejected.
        val utf8 = toUtf8(raw)
        val parsed = M3uParser.parse(utf8.inputStream())
        val fallbackName = displayName.substringBeforeLast('.').ifBlank { displayName }
        val parent = chooseParent(parsed.entries, knownParent, snapshot)
        val firstPass = parsed.entries.map { snapshot.index.resolve(it, parent) }
        val mappings = inferRootMappings(
            parsed.entries.filterIndexed { i, _ -> firstPass[i] !is LocalPathResolution.Resolved },
            snapshot,
            existing = target?.rootMappings.orEmpty().map { it.toDomain() },
        )
        // Hash, name and the first resolution come from the domain preview.
        val preview = LocalImportPreview.prepare(utf8, fallbackName, snapshot.index, parent)
        val entries = preview.entries.map { row ->
            val resolution = row.resolution.takeIf { it is LocalPathResolution.Resolved }
                ?: mappings.takeIf { it.isNotEmpty() }?.let { snapshot.index.resolve(row.source, parent, it) }
                ?: row.resolution
            row.source.toStored(resolution)
        }

        val existing = target ?: findExisting(context, uri, preview.name)
        val unchanged = existing != null && existing.contentSha256 == preview.contentSha256 && existing.entries == entries
        val playlist = ImportedPlaylist(
            id = existing?.id ?: UUID.randomUUID().toString().replace("-", "").take(16),
            name = preview.name,
            sourceUri = uri.toString(),
            importedAt = System.currentTimeMillis(),
            revision = if (unchanged) existing!!.revision else (existing?.revision ?: 0L) + 1,
            entries = entries,
            contentSha256 = preview.contentSha256,
            parentVolume = parent?.volume,
            parentPath = parent?.relativePath,
            rootMappings = mappings.map { ImportedRootMapping(it.sourceRoot, it.target.volume, it.target.relativePath) },
            libraryStamp = snapshot.stamp,
            coverUrl = coverFor(context, entries) ?: existing?.coverUrl,
        )
        ImportedPlaylistStore.upsert(playlist)
        Log.i(TAG, "Imported ${playlist.name}: ${playlist.availableCount}/${entries.size} available")
        return playlist
    }

    /**
     * Resolves stored entries again against [snapshot], with the folder and
     * root mappings recorded at import. Used when MediaStore changed under an
     * imported playlist (files moved, added, or rescanned with new ids).
     */
    fun reresolve(playlist: ImportedPlaylist, snapshot: LocalMixSnapshot): ImportedPlaylist {
        val parent = playlist.parentVolume?.let { PlaylistParent(it, playlist.parentPath.orEmpty()) }
        val mappings = playlist.rootMappings.map { it.toDomain() }
        val entries = playlist.entries.map { stored ->
            val source = M3uParser.parse(StringReader(stored.rawPath)).entries.singleOrNull()
                ?.copy(ordinal = stored.ordinal, title = stored.title, durationSeconds = stored.durationSec)
            if (source == null) {
                stored.copy(resolvedLocalUri = null, resolvedPath = null)
            } else {
                val first = snapshot.index.resolve(source, parent)
                val resolution = first.takeIf { it is LocalPathResolution.Resolved }
                    ?: mappings.takeIf { it.isNotEmpty() }?.let { snapshot.index.resolve(source, parent, it) }
                    ?: first
                source.toStored(resolution).copy(rawPath = stored.rawPath)
            }
        }
        return playlist.copy(entries = entries, libraryStamp = snapshot.stamp)
    }

    internal suspend fun coverFor(context: Context, entries: List<ImportedPlaylistEntry>): String? {
        val firstUris = entries.mapNotNull { it.resolvedLocalUri }.distinct().take(12)
        if (firstUris.isEmpty()) return null
        val songs = LocalMediaRepository.songsByContentUris(context, firstUris)
        return firstUris.firstNotNullOfOrNull { songs[it]?.thumbnailUrl?.takeIf(String::isNotBlank) }
    }

    private fun M3uEntry.toStored(resolution: LocalPathResolution): ImportedPlaylistEntry {
        val file = (resolution as? LocalPathResolution.Resolved)?.file
        return ImportedPlaylistEntry(
            ordinal = ordinal,
            rawPath = location,
            title = title,
            durationSec = durationSeconds,
            resolvedLocalUri = file?.songId,
            resolvedPath = file?.locator?.let { it.volume + ":" + it.path },
        )
    }

    private fun ImportedRootMapping.toDomain() = LocalRootMapping(sourceRoot, PlaylistParent(volume, relativePath))

    // ---- Folder and root inference ----

    /**
     * The folder relative entries are read against: the document's own folder
     * when the provider says where it is, unless a folder inferred from the
     * library resolves clearly more of them (the playlist was copied without
     * its music, or the music was moved).
     */
    private fun chooseParent(
        entries: List<M3uEntry>,
        known: PlaylistParent?,
        snapshot: LocalMixSnapshot,
    ): PlaylistParent? {
        val relative = entries.filter { it.kind == M3uLocationKind.LOCAL_PATH && !LocalRootSuggestions.isAbsolute(it.location) }
        if (relative.isEmpty()) return known
        fun resolvedWith(parent: PlaylistParent?) =
            relative.count { snapshot.index.resolve(it, parent) is LocalPathResolution.Resolved }
        val knownCount = known?.let(::resolvedWith) ?: 0
        if (known != null && knownCount == relative.size) return known
        val inferred = inferParent(relative, snapshot) ?: return known
        return if (resolvedWith(inferred) > knownCount) inferred else known
    }

    private fun inferParent(relative: List<M3uEntry>, snapshot: LocalMixSnapshot): PlaylistParent? {
        val votes = HashMap<Pair<String, String>, Int>()
        relative.asSequence().take(VOTE_SAMPLE).forEach { entry ->
            val segments = entry.location.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            val ups = segments.takeWhile { it == ".." }.size
            val rest = segments.drop(ups)
            if (rest.isEmpty() || ".." in rest) return@forEach
            val (volume, folder) = uniqueSuffixTarget(rest, snapshot) ?: return@forEach
            // `D/x/../a.mp3` normalizes to `D/a.mp3` whatever x is, so the
            // unknown folders the playlist climbed out of are placeholders.
            val parentPath = (listOf(folder) + List(ups) { UNKNOWN_SEGMENT }).filter { it.isNotEmpty() }.joinToString("/")
            val key = volume to parentPath
            votes[key] = (votes[key] ?: 0) + 1
        }
        val best = votes.maxByOrNull { it.value } ?: return null
        return PlaylistParent(best.key.first, best.key.second)
    }

    /**
     * Maps the roots of absolute paths that did not resolve (written on a PC,
     * or pointing at a folder that has since moved) to the phone folder that
     * holds the same relative tree, using [LocalRootSuggestions.commonRoots].
     */
    private fun inferRootMappings(
        unresolved: List<M3uEntry>,
        snapshot: LocalMixSnapshot,
        existing: List<LocalRootMapping>,
    ): List<LocalRootMapping> {
        val absolute = unresolved.filter { LocalRootSuggestions.isAbsolute(it.location) }
        if (absolute.isEmpty()) return existing
        val paths = absolute.mapNotNull { pathOf(it) }
        val added = LocalRootSuggestions.commonRoots(absolute.map { it.location }).mapNotNull { root ->
            val canonical = LocalRootSuggestions.canonicalRoot(root)
            val votes = HashMap<Pair<String, String>, Int>()
            paths.asSequence()
                .filter { it.startsWith("$canonical/") }
                .take(VOTE_SAMPLE)
                .forEach { path ->
                    val rest = path.removePrefix("$canonical/").split('/').filter { it.isNotEmpty() && it != "." }
                    if (rest.isEmpty() || ".." in rest) return@forEach
                    val target = uniqueSuffixTarget(rest, snapshot) ?: return@forEach
                    votes[target] = (votes[target] ?: 0) + 1
                }
            val best = votes.maxByOrNull { it.value } ?: return@mapNotNull null
            LocalRootMapping(canonical, PlaylistParent(best.key.first, best.key.second))
        }
        return LocalRootSuggestions.merge(existing, added)
    }

    private fun pathOf(entry: M3uEntry): String? {
        val raw = if (entry.kind == M3uLocationKind.FILE_URI) {
            runCatching { URI(entry.location).path }.getOrNull() ?: return null
        } else entry.location
        return raw.replace('\\', '/')
    }

    /**
     * The one library folder whose files end with [segments] (`Club/a.mp3`),
     * as volume to folder path; null when none or several do.
     */
    private fun uniqueSuffixTarget(segments: List<String>, snapshot: LocalMixSnapshot): Pair<String, String>? {
        val name = segments.last().lowercase(Locale.ROOT)
        val candidates = snapshot.byName[name] ?: snapshot.byName[nfc(name)] ?: return null
        val suffix = nfc(segments.joinToString("/")).lowercase(Locale.ROOT)
        var found: IndexedLocalFile? = null
        for (file in candidates) {
            val path = nfc(file.locator.path).lowercase(Locale.ROOT)
            if (path == suffix || path.endsWith("/$suffix")) {
                if (found != null) return null
                found = file
            }
        }
        val file = found ?: return null
        val path = nfc(file.locator.path)
        if (path.length < suffix.length) return null
        val folder = path.substring(0, path.length - suffix.length).trimEnd('/')
        return file.locator.volume to folder
    }

    private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

    // ---- Documents ----

    private fun readDocument(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= LocalImportPreview.MAX_DOCUMENT_BYTES) { "Playlist document exceeds 16 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun toUtf8(bytes: ByteArray): ByteArray {
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        if (runCatching { strict.decode(ByteBuffer.wrap(bytes)) }.isSuccess) return bytes
        val legacy = runCatching { Charset.forName("windows-1250") }.getOrDefault(Charsets.ISO_8859_1)
        return String(bytes, legacy).toByteArray(Charsets.UTF_8)
    }

    /**
     * The imported playlist a newly read file updates: same file first (URI,
     * provider document, or path; a folder grant and a file pick give the same
     * file two URIs), the name only when nothing says the files differ.
     */
    private fun findExisting(context: Context, uri: Uri, name: String): ImportedPlaylist? {
        val stored = ImportedPlaylistStore.playlists.value
        val known = stored.map { PlaylistSourceMatcher.Known(it.id, it.name, sourceOf(Uri.parse(it.sourceUri))) }
        val found = PlaylistSourceMatcher.find(sourceOf(uri), name, known) { candidate ->
            // A same-named playlist elsewhere is only taken over when its own file is gone.
            displayName(context, Uri.parse(candidate.source.uri)) == null
        } ?: return null
        return stored.firstOrNull { it.id == found.id }
    }

    /** Where a playlist document lives, for [PlaylistSourceMatcher]. */
    internal fun sourceOf(uri: Uri): PlaylistSource {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val path = documentId?.let { id ->
            when {
                id.startsWith("raw:") -> LocalMixLibrary.locatorFromPath(id.removePrefix("raw:"))
                    ?.let { "${it.volume}:${it.path}" }
                uri.authority == "com.android.externalstorage.documents" -> {
                    val parts = id.split(':', limit = 2)
                    if (parts.size != 2) null else {
                        val volume = if (parts[0] == "primary") "external_primary" else parts[0].lowercase(Locale.ROOT)
                        "$volume:${parts[1].trim('/')}"
                    }
                }
                else -> null
            }
        }
        return PlaylistSource(
            uri = uri.toString(),
            document = documentId?.let { "${uri.authority}|$it" },
            path = path,
        )
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    /**
     * The folder a document sits in, when its provider says so: external
     * storage documents (`primary:Music/Club.m3u8`) and raw paths from the
     * Downloads provider. Null for anything else, which is then inferred.
     */
    internal fun documentParent(uri: Uri): PlaylistParent? = runCatching {
        val id = DocumentsContract.getDocumentId(uri)
        when {
            id.startsWith("raw:") -> LocalMixLibrary.locatorFromPath(id.removePrefix("raw:"))
                ?.let { PlaylistParent(it.volume, it.relativePath) }
            uri.authority == "com.android.externalstorage.documents" -> {
                val parts = id.split(':', limit = 2)
                if (parts.size != 2) return@runCatching null
                val volume = if (parts[0] == "primary") "external_primary" else parts[0].lowercase(Locale.ROOT)
                PlaylistParent(volume, parts[1].trim('/').substringBeforeLast('/', ""))
            }
            else -> null
        }
    }.getOrNull()

    private fun listPlaylistDocuments(context: Context, tree: Uri): List<Pair<Uri, String>> {
        val found = mutableListOf<Pair<Uri, String>>()
        fun walk(documentId: String, depth: Int) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val folders = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    if (id == null) {
                        // A row without an id cannot be opened.
                    } else if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        folders += id
                    } else if (isPlaylistName(name)) {
                        found += DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    }
                }
                if (depth < 3) folders.forEach { walk(it, depth + 1) }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return found.sortedBy { it.second.lowercase(Locale.ROOT) }
    }
}
