package com.music.bitchord.localmix

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.localmix.IndexedLocalFile
import moe.rukamori.archivetune.localmix.LocalFileLocator
import moe.rukamori.archivetune.localmix.LocalPathIndex
import java.util.Locale

/**
 * Every audio file MediaStore knows, as path locators for [LocalPathIndex].
 *
 * Paths only: titles and artwork are read later, and only for the rows a
 * playlist actually names, through [LocalMediaRepository.songsByContentUris].
 */
class LocalMixSnapshot(
    val stamp: String,
    val files: List<IndexedLocalFile>,
) {
    val index: LocalPathIndex by lazy { LocalPathIndex(files) }

    /** Lower-cased display name to every file of that name, for suffix voting. */
    val byName: Map<String, List<IndexedLocalFile>> by lazy {
        files.groupBy { it.locator.displayName.lowercase(Locale.ROOT) }
    }
}

/**
 * Caches MediaStore paths until the library changes, so imports and playlist
 * pages can share a scan.
 */
object LocalMixLibrary {
    private const val TAG = "LocalMix"

    private val mutex = Mutex()
    private var cached: LocalMixSnapshot? = null

    /**
     * Identifies the current MediaStore contents. Changes whenever a file is
     * added, removed or moved on any external volume (generation, API 30+), or
     * when the whole database is rebuilt (version).
     */
    fun libraryStamp(context: Context): String = runCatching {
        val version = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getVersion(context) else "legacy"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val generations = MediaStore.getExternalVolumeNames(context).sorted().joinToString(",") { volume ->
                "$volume=${MediaStore.getGeneration(context, volume)}"
            }
            "$version|$generations"
        } else {
            // No generation counter before R: the stamp cannot see changes, so
            // older devices fall back to a short time window.
            "$version|t${System.currentTimeMillis() / 60_000}"
        }
    }.getOrDefault("unknown|${System.currentTimeMillis()}")

    suspend fun snapshot(context: Context): LocalMixSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stamp = libraryStamp(context)
            cached?.takeIf { it.stamp == stamp }?.let { return@withLock it }
            // An empty scan is usually a missing permission, not an empty
            // library, so it is never kept: the next call looks again.
            LocalMixSnapshot(stamp, scan(context)).also { if (it.files.isNotEmpty()) cached = it }
        }
    }

    /** Forgets the cached snapshot, e.g. after the storage permission was granted. */
    fun invalidate() {
        cached = null
    }

    private fun scan(context: Context): List<IndexedLocalFile> {
        if (!LocalMediaRepository.hasStoragePermission(context)) return emptyList()
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.DATA)
            if (modern) {
                add(MediaStore.Audio.Media.VOLUME_NAME)
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            }
        }.toTypedArray()
        val selection = if (modern) "${MediaStore.MediaColumns.IS_PENDING} = 0" else null
        val files = ArrayList<IndexedLocalFile>(4096)
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val volumeCol = if (modern) cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.VOLUME_NAME) else -1
                val relativeCol = if (modern) cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH) else -1
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val fromData = cursor.getString(dataCol)?.let(::locatorFromPath)
                    val name = cursor.getString(nameCol)?.takeIf { it.isNotBlank() } ?: fromData?.displayName
                    val volume = if (volumeCol >= 0) cursor.getString(volumeCol)?.lowercase(Locale.ROOT) else null
                    val relative = if (relativeCol >= 0) cursor.getString(relativeCol) else null
                    val locator = when {
                        name == null -> null
                        volume != null && relative != null -> LocalFileLocator(volume, relative.trim('/'), name)
                        else -> fromData
                    }
                    if (locator != null) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        files += IndexedLocalFile(uri, locator)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "MediaStore path scan failed: ${it.message}") }
        return files
    }

    /** `/storage/emulated/0/Music/a.mp3` to `external_primary` + `Music` + `a.mp3`. */
    internal fun locatorFromPath(path: String): LocalFileLocator? {
        val normalized = path.replace('\\', '/')
        val (volume, relative) = when {
            normalized.startsWith("/storage/emulated/0/") ->
                "external_primary" to normalized.removePrefix("/storage/emulated/0/")
            normalized.startsWith("/sdcard/") -> "external_primary" to normalized.removePrefix("/sdcard/")
            normalized.startsWith("/storage/") -> {
                val parts = normalized.removePrefix("/storage/").split('/', limit = 2)
                if (parts.size != 2 || parts[0] == "emulated" || parts[0] == "self") return null
                parts[0].lowercase(Locale.ROOT) to parts[1]
            }
            else -> return null
        }
        val folder = relative.substringBeforeLast('/', "")
        val name = relative.substringAfterLast('/')
        if (name.isBlank()) return null
        return LocalFileLocator(volume, folder, name)
    }
}
