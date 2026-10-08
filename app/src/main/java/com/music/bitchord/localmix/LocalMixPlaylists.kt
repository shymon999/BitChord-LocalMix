package com.music.bitchord.localmix

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.SPOTIFY_MISSING_PREFIX
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** An imported playlist as a page: one [Song] per entry, in the file's order. */
data class LoadedImportedPlaylist(
    val playlist: ImportedPlaylist,
    val songs: List<Song>,
    val missingCount: Int,
    val coverUrl: String?,
)

/**
 * Builds the rows of an imported playlist's page.
 *
 * Available entries become the same [Song] the Local Music folder shows
 * (via [LocalMediaRepository.songsByContentUris], batched, cached per
 * MediaStore state), so playback treats them exactly like any other local
 * file. Every entry is its own row, repeats included: each carries a
 * [Song.setVideoId] unique to its position, so two rows of the same file are
 * never equal and a tap on the second one starts from the second one.
 *
 * Missing entries stay in the list as rows marked with
 * [SPOTIFY_MISSING_PREFIX], which the shared song row already draws dimmed
 * and the player already skips, titled from EXTINF or the file name.
 */
object LocalMixPlaylists {
    const val ENTRY_KEY_PREFIX = "localmix:"

    private val songCache = HashMap<String, Song>()
    private var songCacheStamp: String? = null
    private val cacheLock = Any()

    suspend fun load(context: Context, playlistId: String): LoadedImportedPlaylist? = withContext(Dispatchers.IO) {
        var playlist = ImportedPlaylistStore.get(playlistId) ?: return@withContext null
        val stamp = LocalMixLibrary.libraryStamp(context)
        var songs = lookup(context, playlist, stamp)
        val stale = playlist.entries.any { it.resolvedLocalUri != null && it.resolvedLocalUri !in songs }
        // The library changed since the playlist was resolved: files that
        // were missing may have arrived, and moved files have new ids. One
        // path snapshot (cached per stamp) re-resolves the whole list.
        // Without the storage permission every lookup comes back empty, which
        // is not the files going missing: the stored resolution is kept.
        val canRead = LocalMediaRepository.hasStoragePermission(context)
        // Another MediaStore database (a backup from another phone, or a
        // rebuilt one): the same content uri can be a different file there.
        val otherDatabase = playlist.libraryStamp.substringBefore('|') != stamp.substringBefore('|')
        if (canRead && (stale || otherDatabase || (playlist.missingCount > 0 && playlist.libraryStamp != stamp))) {
            val snapshot = LocalMixLibrary.snapshot(context)
            if (snapshot.files.isEmpty()) return@withContext build(playlist, songs, context)
            val updated = LocalMixImporter.reresolve(playlist, snapshot)
            val withCover = updated.copy(
                coverUrl = LocalMixImporter.coverFor(context, updated.entries) ?: updated.coverUrl,
            )
            if (withCover != playlist) ImportedPlaylistStore.upsert(withCover)
            playlist = withCover
            songs = lookup(context, playlist, snapshot.stamp)
        }
        build(playlist, songs, context)
    }

    private fun build(playlist: ImportedPlaylist, songs: Map<String, Song>, context: Context): LoadedImportedPlaylist {
        val rows = playlist.entries.map { entry ->
            val key = "$ENTRY_KEY_PREFIX${playlist.id}:${entry.ordinal}"
            val found = entry.resolvedLocalUri?.let { songs[it] }
            found?.copy(setVideoId = key) ?: missingSong(context, entry, key)
        }
        val missing = rows.count { it.videoId.startsWith(SPOTIFY_MISSING_PREFIX) }
        val cover = rows.firstNotNullOfOrNull { song ->
            song.thumbnailUrl?.takeIf { it.isNotBlank() && !song.videoId.startsWith(SPOTIFY_MISSING_PREFIX) }
        } ?: playlist.coverUrl
        return LoadedImportedPlaylist(playlist, rows, missing, cover)
    }

    /** Songs for the playlist's resolved URIs, from the cache where it is still current. */
    private suspend fun lookup(context: Context, playlist: ImportedPlaylist, stamp: String): Map<String, Song> {
        val wanted = playlist.entries.mapNotNull { it.resolvedLocalUri }.toSet()
        val known = HashMap<String, Song>()
        val toQuery = synchronized(cacheLock) {
            if (songCacheStamp != stamp) {
                songCache.clear()
                songCacheStamp = stamp
            }
            wanted.filter { uri -> songCache[uri]?.also { known[uri] = it } == null }
        }
        if (toQuery.isNotEmpty()) {
            val fetched = LocalMediaRepository.songsByContentUris(context, toQuery)
            synchronized(cacheLock) { if (songCacheStamp == stamp) songCache.putAll(fetched) }
            known.putAll(fetched)
        }
        return known
    }

    fun invalidate() = synchronized(cacheLock) {
        songCache.clear()
        songCacheStamp = null
    }

    private fun missingSong(context: Context, entry: ImportedPlaylistEntry, key: String): Song {
        val fileName = entry.rawPath.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')
            .ifBlank { entry.rawPath }
        val extinf = entry.title?.takeIf { it.isNotBlank() }
        // "Artist - Title" is what most players write into EXTINF.
        val split = extinf?.split(" - ", limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
        val title = split?.get(1) ?: extinf ?: fileName
        val artist = split?.get(0) ?: context.getString(R.string.localmix_missing_file)
        val duration = entry.durationSec?.takeIf { it > 0 }?.toLong()?.let { seconds ->
            String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        }
        return Song(
            videoId = SPOTIFY_MISSING_PREFIX + key,
            title = title.trim(),
            artist = artist.trim(),
            thumbnailUrl = null,
            durationText = duration,
            setVideoId = key,
        )
    }
}
