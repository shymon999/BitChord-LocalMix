package com.music.bitchord.localmix

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.localmix.home.DiskArtist
import moe.rukamori.archivetune.localmix.home.DiskTrack
import moe.rukamori.archivetune.localmix.home.HomeSection
import moe.rukamori.archivetune.localmix.home.LocalHomeSections
import moe.rukamori.archivetune.localmix.home.PlayedTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * The home sections built from what is on the device: recently and most
 * played local files (from [ListeningStats]), recently added files and the
 * artists with the most files (from MediaStore).
 *
 * Computed on [Dispatchers.IO] and published through [shelves]; the home
 * screen never waits for them. Only the sections the user turned on are
 * computed, and the MediaStore read is kept until the library changes.
 *
 * Every shelf opens through `openDetail`: an artist card opens that artist's
 * files ([ARTIST_PREFIX]), the heading's "show all" opens the whole section
 * ([PAGE_PREFIX]), and a track card plays the file itself (see [song]).
 */
object LocalMixHomeSections {
    private const val TAG = "LocalMix"

    const val PAGE_PREFIX = "localmix:home:"
    const val ARTIST_PREFIX = "localmix:artist:"

    /** Cards on a shelf. */
    private const val SHELF_SIZE = 20

    /** Tracks on a section's "show all" page. */
    private const val PAGE_SIZE = 100

    private const val ARTIST_COUNT = 30

    /** Skips a refresh asked for this soon after the last one (tab switches). */
    private const val MIN_REFRESH_GAP_MS = 30_000L

    private class Built(val shelf: HomeShelf, val songs: List<Song>)

    private val built = MutableStateFlow<Map<HomeSection, Built>>(emptyMap())
    private val _shelves = MutableStateFlow<Map<HomeSection, HomeShelf>>(emptyMap())

    /** The computed shelves by section; a section with nothing to show is absent. */
    val shelves: StateFlow<Map<HomeSection, HomeShelf>> = _shelves.asStateFlow()

    /** The tracks on the shelves, by video id, so a tapped card plays its file. */
    private val songsById = ConcurrentHashMap<String, Song>()

    /** Artists from the last MediaStore read, by key. */
    @Volatile
    private var artistsByKey: Map<String, DiskArtist> = emptyMap()

    /** MediaStore's songs and the library stamp they were read at. */
    @Volatile
    private var disk: Pair<String, List<Song>>? = null

    private val mutex = Mutex()

    @Volatile
    private var lastRefresh: Pair<Set<HomeSection>, Long>? = null

    fun isBrowseId(browseId: String): Boolean =
        browseId.startsWith(PAGE_PREFIX) || browseId.startsWith(ARTIST_PREFIX)

    /** The local file behind a track card on one of these shelves. */
    fun song(videoId: String): Song? = songsById[videoId]

    /**
     * Recomputes the [sections] that are local. Without [force] nothing happens
     * before the first computation, nor within [MIN_REFRESH_GAP_MS] of the last.
     */
    suspend fun refresh(context: Context, sections: Set<HomeSection>, force: Boolean = true): Unit =
        withContext(Dispatchers.IO) {
            val wanted = sections.filterTo(mutableSetOf()) { it.isLocal }
            val now = System.currentTimeMillis()
            if (!force) {
                // The first computation is the start-up one, timed by the ViewModel
                // to leave the first paint alone; a tab switch never jumps ahead of it.
                val last = lastRefresh ?: return@withContext
                if (last.first == wanted && now - last.second < MIN_REFRESH_GAP_MS) return@withContext
            }
            mutex.withLock {
                lastRefresh = wanted to now
                if (wanted.isEmpty() || !LocalMediaRepository.hasStoragePermission(context)) {
                    publish(emptyMap())
                    return@withLock
                }
                runCatching { build(context, wanted) }
                    .onSuccess(::publish)
                    .onFailure { Log.w(TAG, "Home sections failed: ${it.message}") }
            }
            Unit
        }

    private fun publish(result: Map<HomeSection, Built>) {
        songsById.clear()
        result.values.forEach { section -> section.songs.take(SHELF_SIZE).forEach { songsById[it.videoId] = it } }
        built.value = result
        _shelves.value = result.mapValues { it.value.shelf }
    }

    private suspend fun build(context: Context, wanted: Set<HomeSection>): Map<HomeSection, Built> {
        val result = LinkedHashMap<HomeSection, Built>()
        if (HomeSection.LOCAL_RECENT in wanted || HomeSection.LOCAL_MOST_PLAYED in wanted) {
            val (tracks, songById) = playedLocally(context)
            if (HomeSection.LOCAL_RECENT in wanted) {
                val songs = LocalHomeSections.recentlyPlayed(tracks, PAGE_SIZE).mapNotNull(songById::get).distinctByFile()
                songSection(context, HomeSection.LOCAL_RECENT, songs)?.let { result[HomeSection.LOCAL_RECENT] = it }
            }
            if (HomeSection.LOCAL_MOST_PLAYED in wanted) {
                val songs = LocalHomeSections.mostPlayed(tracks, PAGE_SIZE).mapNotNull(songById::get).distinctByFile()
                songSection(context, HomeSection.LOCAL_MOST_PLAYED, songs)?.let { result[HomeSection.LOCAL_MOST_PLAYED] = it }
            }
        }
        if (HomeSection.LOCAL_RECENTLY_ADDED in wanted || HomeSection.LOCAL_ARTISTS in wanted) {
            val songs = diskSongs(context)
            val files = songs.map { DiskTrack(it.fileKey(), it.artist, it.localDateAddedSeconds ?: 0L) }
            val byFile = songs.associateBy { it.fileKey() }
            if (HomeSection.LOCAL_RECENTLY_ADDED in wanted) {
                val added = LocalHomeSections.recentlyAdded(files, PAGE_SIZE).mapNotNull(byFile::get)
                songSection(context, HomeSection.LOCAL_RECENTLY_ADDED, added)
                    ?.let { result[HomeSection.LOCAL_RECENTLY_ADDED] = it }
            }
            if (HomeSection.LOCAL_ARTISTS in wanted) {
                val artists = LocalHomeSections.artists(files, ARTIST_COUNT)
                artistsByKey = artists.associateBy { it.key }
                artistSection(context, artists, byFile)?.let { result[HomeSection.LOCAL_ARTISTS] = it }
            }
        }
        return result
    }

    /**
     * Tracks from the listening record that play from a file on the device:
     * the file itself (its id is its content URI) or a download of a YouTube
     * track. Files deleted since they were played drop out here.
     */
    private suspend fun playedLocally(context: Context): Pair<List<PlayedTrack>, Map<String, Song>> {
        val saved = Downloads.saved.value
        val entries = ListeningStats.allTracks()
        val uriById = HashMap<String, String>()
        entries.forEach { entry ->
            val uri = if (entry.id.startsWith(CONTENT_SCHEME)) entry.id else saved[entry.id]
            if (uri != null && uri.startsWith(CONTENT_SCHEME)) uriById[entry.id] = uri
        }
        if (uriById.isEmpty()) return emptyList<PlayedTrack>() to emptyMap()
        val byUri = LocalMediaRepository.songsByContentUris(context, uriById.values.toSet())
        val songById = uriById.mapNotNull { (id, uri) -> byUri[uri]?.let { id to it } }.toMap()
        val tracks = entries.filter { it.id in songById }
            .map { PlayedTrack(it.id, it.plays, it.ms, it.last) }
        return tracks to songById
    }

    /** Every local song, read again only when MediaStore reports a change. */
    private suspend fun diskSongs(context: Context): List<Song> {
        val stamp = LocalMixLibrary.libraryStamp(context)
        disk?.takeIf { it.first == stamp }?.let { return it.second }
        val songs = LocalMediaRepository.getLocalMusic(context)
        // An empty read is usually a missing permission: never kept.
        if (songs.isNotEmpty()) disk = stamp to songs
        return songs
    }

    private fun songSection(context: Context, section: HomeSection, songs: List<Song>): Built? {
        if (songs.isEmpty()) return null
        val shelf = HomeShelf(
            title = context.getString(titleOf(section)),
            items = songs.take(SHELF_SIZE).map { song ->
                ShelfItem(
                    title = song.title,
                    subtitle = song.artist,
                    thumbnailUrl = song.thumbnailUrl,
                    videoId = song.videoId,
                    browseId = null,
                )
            },
            showAllBrowseId = PAGE_PREFIX + section.key,
        )
        return Built(shelf, songs)
    }

    private fun artistSection(context: Context, artists: List<DiskArtist>, byFile: Map<String, Song>): Built? {
        if (artists.isEmpty()) return null
        val shelf = HomeShelf(
            title = context.getString(titleOf(HomeSection.LOCAL_ARTISTS)),
            items = artists.map { artist ->
                val songs = artist.trackIds.mapNotNull(byFile::get)
                ShelfItem(
                    title = artist.name,
                    subtitle = context.resources.getQuantityString(
                        R.plurals.track_count_plural,
                        artist.trackIds.size,
                        artist.trackIds.size,
                    ),
                    thumbnailUrl = songs.firstOrNull { !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl,
                    videoId = null,
                    browseId = ARTIST_PREFIX + artist.key,
                )
            },
        )
        return Built(shelf, emptyList())
    }

    /**
     * The tracks behind [browseId] and the page's title: a whole section, or
     * one artist's files sorted by album and title. Null when [browseId] is
     * not one of these.
     */
    suspend fun page(context: Context, browseId: String): Pair<String?, List<Song>>? = withContext(Dispatchers.IO) {
        when {
            browseId.startsWith(PAGE_PREFIX) -> {
                val section = HomeSection.fromKey(browseId.removePrefix(PAGE_PREFIX)) ?: return@withContext null
                var songs = built.value[section]?.songs
                if (songs == null) {
                    // Opened before the section was computed (or after it was turned off).
                    mutex.withLock { songs = runCatching { build(context, setOf(section))[section]?.songs }.getOrNull() }
                }
                context.getString(titleOf(section)) to songs.orEmpty()
            }
            browseId.startsWith(ARTIST_PREFIX) -> {
                val key = browseId.removePrefix(ARTIST_PREFIX)
                val songs = diskSongs(context)
                    .filter { LocalHomeSections.artistKey(it.artist) == key }
                    .sortedWith(compareBy<Song>({ it.albumName?.lowercase().orEmpty() }, { it.title.lowercase() }))
                val name = artistsByKey[key]?.name ?: songs.firstNotNullOfOrNull { LocalHomeSections.leadArtist(it.artist) }
                name to songs
            }
            else -> null
        }
    }

    fun titleOf(section: HomeSection): Int = when (section) {
        HomeSection.RECENTS -> R.string.localmix_home_section_recents
        HomeSection.MY_PLAYLISTS -> R.string.localmix_home_my_playlists
        HomeSection.FAVORITE_PLAYLISTS -> R.string.localmix_home_favorite_playlists
        HomeSection.LOCAL_RECENT -> R.string.localmix_home_section_local_recent
        HomeSection.LOCAL_MOST_PLAYED -> R.string.localmix_home_section_local_most_played
        HomeSection.LOCAL_RECENTLY_ADDED -> R.string.localmix_home_section_local_added
        HomeSection.LOCAL_ARTISTS -> R.string.localmix_home_section_local_artists
        HomeSection.YOUTUBE_FEED -> R.string.localmix_home_section_youtube
    }

    private fun Song.fileKey(): String = localUri ?: videoId

    /** One card per file: a download can be in the record under both its YouTube id and its URI. */
    private fun List<Song>.distinctByFile(): List<Song> = distinctBy { it.fileKey() }

    private const val CONTENT_SCHEME = "content://"
}
