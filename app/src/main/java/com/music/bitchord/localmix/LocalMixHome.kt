package com.music.bitchord.localmix

import android.content.Context
import com.music.bitchord.R
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.UiState
import moe.rukamori.archivetune.localmix.home.HomeLayout
import moe.rukamori.archivetune.localmix.home.HomeSection
import moe.rukamori.archivetune.localmix.home.SectionStyle

/**
 * The home screen as the user laid it out (Settings > Home screen): YouTube
 * Music's feed split into its Recents shelf and everything else, plus the
 * "My playlists" shelf of imported M3U playlists and the on-device sections of
 * [LocalMixHomeSections], each shown or hidden and put in the chosen order by
 * [HomeLayout].
 *
 * Applied on top of the feed the ViewModel loads rather than inside it, so
 * paging, refresh and the YouTube request flow stay exactly as they were.
 */
object LocalMixHome {
    /** The title YouTube's recently played shelf is published under, see `YtMusicRepository`. */
    private const val RECENTS_TITLE = "Recents"

    fun arrange(
        context: Context,
        feed: UiState<List<HomeShelf>>,
        layout: HomeLayout,
        playlists: List<ImportedPlaylist>,
        onDevice: Map<HomeSection, HomeShelf> = emptyMap(),
        favorites: Set<String> = emptySet(),
    ): UiState<List<HomeShelf>> {
        val parts = HashMap<HomeSection, List<HomeShelf>>()
        playlistShelf(context, R.string.localmix_home_favorite_playlists, playlists.filter { it.id in favorites })
            ?.let { parts[HomeSection.FAVORITE_PLAYLISTS] = listOf(it) }
        playlistShelf(context, R.string.localmix_home_my_playlists, playlists)
            ?.let { parts[HomeSection.MY_PLAYLISTS] = listOf(it) }
        onDevice.forEach { (section, shelf) -> if (section.isLocal) parts[section] = listOf(shelf) }
        // Everything that does not come from YouTube, already in layout order.
        val local = layout.arrange(styled(parts, layout))
        val youtubeShown = layout.isVisible(HomeSection.RECENTS) || layout.isVisible(HomeSection.YOUTUBE_FEED)
        return when (feed) {
            is UiState.Success -> {
                val (recents, rest) = feed.data.partition { it.title.equals(RECENTS_TITLE, ignoreCase = true) }
                parts[HomeSection.RECENTS] = recents
                parts[HomeSection.YOUTUBE_FEED] = rest
                UiState.Success(layout.arrange(styled(parts, layout)))
            }
            // Nothing from YouTube is wanted: no reason to wait on it.
            is UiState.Loading -> if (!youtubeShown) UiState.Success(local) else feed
            // Offline or signed out: the imported playlists and local sections still work.
            is UiState.Error -> if (local.isNotEmpty() || !youtubeShown) UiState.Success(local) else feed
        }
    }

    /**
     * Whether the YouTube feed may page in more shelves as the list nears its
     * end. Only when it is the last visible section: below it, every new page
     * would push the sections after it further down while they are on screen.
     */
    fun feedPages(layout: HomeLayout): Boolean = layout.visibleSections.lastOrNull() == HomeSection.YOUTUBE_FEED

    /** Each section's shelves marked with the style the user picked for it. */
    private fun styled(parts: Map<HomeSection, List<HomeShelf>>, layout: HomeLayout): Map<HomeSection, List<HomeShelf>> =
        parts.mapValues { (section, shelves) ->
            val grid = layout.styleOf(section) == SectionStyle.GRID
            if (grid) shelves.map { it.copy(grid = true) } else shelves
        }

    /** Whether the home screen should show the Recents skeleton while it loads. */
    fun recentsLeads(layout: HomeLayout): Boolean = layout.visibleSections.firstOrNull() == HomeSection.RECENTS

    private fun playlistShelf(context: Context, titleRes: Int, playlists: List<ImportedPlaylist>): HomeShelf? {
        if (playlists.isEmpty()) return null
        return HomeShelf(
            title = context.getString(titleRes),
            items = playlists.sortedBy { it.name.lowercase() }.map { playlist ->
                ShelfItem(
                    title = playlist.name,
                    subtitle = context.resources.getQuantityString(
                        R.plurals.track_count_plural,
                        playlist.entries.size,
                        playlist.entries.size,
                    ),
                    thumbnailUrl = playlist.coverUrl,
                    videoId = null,
                    browseId = playlist.browseId,
                )
            },
        )
    }
}
