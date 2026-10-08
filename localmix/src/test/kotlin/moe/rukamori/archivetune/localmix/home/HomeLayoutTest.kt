package moe.rukamori.archivetune.localmix.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private val recents = HomeSection.RECENTS
    private val mine = HomeSection.MY_PLAYLISTS
    private val feed = HomeSection.YOUTUBE_FEED
    private val localRecent = HomeSection.LOCAL_RECENT
    private val mostPlayed = HomeSection.LOCAL_MOST_PLAYED
    private val added = HomeSection.LOCAL_RECENTLY_ADDED
    private val artists = HomeSection.LOCAL_ARTISTS
    private val local = listOf(localRecent, mostPlayed, added, artists)
    private val fav = HomeSection.FAVORITE_PLAYLISTS

    @Test fun defaultShowsEverySectionWithMyPlaylistsAfterRecents() {
        assertEquals(listOf(recents, mine, fav, feed), HomeLayout.DEFAULT.visibleSections)
    }

    @Test fun blankOrMissingValueIsTheDefault() {
        assertSame(HomeLayout.DEFAULT, HomeLayout.parse(null))
        assertSame(HomeLayout.DEFAULT, HomeLayout.parse(""))
    }

    @Test fun serializeRoundTrips() {
        val layout = HomeLayout.DEFAULT.moved(feed, -7).withVisible(recents, false)
        assertEquals(
            "youtube_feed:1,recents:0,my_playlists:1,favorite_playlists:1,local_recent:0,local_most_played:0," +
                "local_added:0,local_artists:0",
            layout.serialize(),
        )
        assertEquals(layout, HomeLayout.parse(layout.serialize()))
    }

    @Test fun parseDropsUnknownAndRepeatedKeysAndAppendsNewSections() {
        val layout = HomeLayout.parse("mixes:1, youtube_feed:0 ,youtube_feed:1,recents:1")
        // my_playlists and the local sections follow recents, as in the default order.
        assertEquals(listOf(feed, recents, mine, fav) + local, layout.sections.map { it.section })
        assertFalse(layout.isVisible(feed))
        assertTrue(layout.isVisible(mine))
    }

    @Test fun moveIsClampedToTheEnds() {
        assertSame(HomeLayout.DEFAULT, HomeLayout.DEFAULT.moved(recents, -1))
        assertSame(HomeLayout.DEFAULT, HomeLayout.DEFAULT.moved(feed, 5).moved(feed, 0))
        assertEquals(listOf(mine, recents, fav, feed), HomeLayout.DEFAULT.moved(mine, -1).visibleSections)
        assertEquals(listOf(recents, fav, feed, mine), HomeLayout.DEFAULT.moved(mine, 6).visibleSections)
    }

    @Test fun arrangeFollowsOrderAndSkipsHiddenSections() {
        val parts = mapOf(
            recents to listOf("Recents"),
            mine to listOf("Moje playlisty"),
            feed to listOf("Quick picks", "Mixed for you"),
        )
        assertEquals(
            listOf("Moje playlisty", "Quick picks", "Mixed for you"),
            HomeLayout.DEFAULT.withVisible(recents, false).arrange(parts),
        )
        assertEquals(
            listOf("Quick picks", "Mixed for you", "Recents"),
            HomeLayout.DEFAULT.moved(recents, 7).withVisible(mine, false).arrange(parts),
        )
    }

    @Test fun arrangeToleratesMissingParts() {
        assertEquals(listOf("Moje playlisty"), HomeLayout.DEFAULT.arrange(mapOf(mine to listOf("Moje playlisty"))))
    }

    @Test fun localSectionsAreHiddenByDefaultAndSitBeforeTheYoutubeFeed() {
        assertEquals(listOf(recents, mine, fav) + local + feed, HomeLayout.DEFAULT.sections.map { it.section })
        local.forEach { assertFalse(HomeLayout.DEFAULT.isVisible(it)) }
        assertEquals(local.toSet(), HomeSection.LOCAL)
        assertEquals(
            listOf(recents, mine, fav, mostPlayed, feed),
            HomeLayout.DEFAULT.withVisible(mostPlayed, true).visibleSections,
        )
    }

    @Test fun sectionsNewToAStoredValueKeepTheUsersOrderAroundThem() {
        // Saved before the local sections existed, with the feed moved to the top.
        val old = HomeLayout.parse("youtube_feed:1,my_playlists:1,recents:0")
        assertEquals(listOf(feed, mine, fav) + local + recents, old.sections.map { it.section })
        assertEquals(listOf(feed, mine, fav), old.visibleSections)
        // Nothing the default puts first is known: the new section leads.
        assertEquals(listOf(recents, mine, fav) + local + feed, HomeLayout.parse("youtube_feed:1").sections.map { it.section })
    }

    @Test fun everySectionCanBeMovedAndTheOrderIsSaved() {
        var layout = HomeLayout.DEFAULT.withVisible(artists, true)
        layout = layout.moved(artists, -10)
        layout = layout.moved(feed, -1)
        assertEquals(listOf(artists, recents, mine, fav, feed), layout.visibleSections)
        assertEquals(layout, HomeLayout.parse(layout.serialize()))
    }

    @Test fun styleIsPerSectionAndRoundTrips() {
        val layout = HomeLayout.DEFAULT.withStyle(mine, SectionStyle.GRID).withStyle(feed, SectionStyle.GRID)
        assertEquals(SectionStyle.GRID, layout.styleOf(mine))
        assertEquals(SectionStyle.CAROUSEL, layout.styleOf(recents))
        assertEquals(
            "recents:1,my_playlists:1:g,favorite_playlists:1,local_recent:0,local_most_played:0,local_added:0," +
                "local_artists:0,youtube_feed:1:g",
            layout.serialize(),
        )
        assertEquals(layout, HomeLayout.parse(layout.serialize()))
        assertEquals(SectionStyle.CAROUSEL, layout.withStyle(mine, SectionStyle.CAROUSEL).styleOf(mine))
    }

    @Test fun unknownStyleFallsBackToTheCarousel() {
        val layout = HomeLayout.parse("my_playlists:1:spiral,recents:0:g")
        assertEquals(SectionStyle.CAROUSEL, layout.styleOf(mine))
        assertEquals(SectionStyle.GRID, layout.styleOf(recents))
        assertFalse(layout.isVisible(recents))
    }

    @Test fun valuesSavedBeforeLocalSectionsAndStylesShowTheSamePage() {
        // As the first home-layout build wrote them.
        listOf(
            // Favourite playlists is on, but empty (nothing marked yet) and so not on the page.
            "recents:1,my_playlists:1,youtube_feed:1" to listOf(recents, mine, fav, feed),
            "youtube_feed:1,recents:0,my_playlists:1" to listOf(feed, mine, fav),
            "my_playlists:0,recents:1,youtube_feed:0" to listOf(fav, recents),
        ).forEach { (stored, shown) ->
            val layout = HomeLayout.parse(stored)
            assertEquals(stored, shown, layout.visibleSections)
            HomeSection.entries.forEach { assertEquals(stored, SectionStyle.CAROUSEL, layout.styleOf(it)) }
            local.forEach { assertFalse(stored, layout.isVisible(it)) }
        }
        // Old value, untouched: same page as the default.
        assertEquals(HomeLayout.DEFAULT, HomeLayout.parse("recents:1,my_playlists:1,youtube_feed:1"))
    }

    @Test fun garbageIsTheDefault() {
        assertEquals(HomeLayout.DEFAULT, HomeLayout.parse("mixes:1,,:,::g"))
        assertEquals(HomeLayout.DEFAULT.visibleSections, HomeLayout.parse("recents").visibleSections)
    }
}
