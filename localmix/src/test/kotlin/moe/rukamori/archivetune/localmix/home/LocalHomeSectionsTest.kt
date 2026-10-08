package moe.rukamori.archivetune.localmix.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalHomeSectionsTest {
    private fun played(id: String, plays: Int, ms: Long, last: Long) = PlayedTrack(id, plays, ms, last)
    private fun file(id: String, artist: String, added: Long) = DiskTrack(id, artist, added)

    @Test fun recentlyPlayedIsNewestFirstAndSkipsNeverPlayed() {
        val tracks = listOf(played("a", 1, 10, 100), played("b", 0, 0, 0), played("c", 3, 5, 300))
        assertEquals(listOf("c", "a"), LocalHomeSections.recentlyPlayed(tracks, 10))
        assertEquals(listOf("c"), LocalHomeSections.recentlyPlayed(tracks, 1))
    }

    @Test fun mostPlayedRanksWholeListensThenMinutesThenRecency() {
        val tracks = listOf(
            played("skipped", 0, 999_999, 900),
            played("two", 2, 10, 100),
            played("twoLonger", 2, 20, 50),
            played("five", 5, 1, 10),
        )
        assertEquals(listOf("five", "twoLonger", "two"), LocalHomeSections.mostPlayed(tracks, 10))
    }

    @Test fun recentlyAddedIsNewestFirst() {
        val files = listOf(file("old", "A", 10), file("new", "B", 30), file("unknownDate", "C", 0), file("mid", "A", 20))
        assertEquals(listOf("new", "mid", "old"), LocalHomeSections.recentlyAdded(files, 10))
    }

    @Test fun artistsGroupByLeadArtistAndRankByFileCount() {
        val files = listOf(
            file("1", "Calvin Harris", 1),
            file("2", "Calvin Harris feat. Ayah Marar", 2),
            file("3", "calvin harris", 3),
            file("4", "Simon & Garfunkel", 4),
            file("5", "Unknown Artist", 5),
            file("6", "Simon & Garfunkel", 6),
            file("7", "Daft Punk", 7),
        )
        val artists = LocalHomeSections.artists(files, 10)
        assertEquals(listOf("Calvin Harris", "Simon & Garfunkel", "Daft Punk"), artists.map { it.name })
        assertEquals(listOf("3", "2", "1"), artists.first().trackIds)
        assertEquals(1, LocalHomeSections.artists(files, 1).size)
    }

    @Test fun leadArtistCutsOnlyUnambiguousSeparators() {
        assertEquals("Calvin Harris", LocalHomeSections.leadArtist("Calvin Harris ft. Ayah Marar"))
        assertEquals("Calvin Harris", LocalHomeSections.leadArtist("Calvin Harris (feat. Ayah Marar)"))
        assertEquals("AC/DC", LocalHomeSections.leadArtist("AC/DC"))
        assertEquals("Earth, Wind & Fire", LocalHomeSections.leadArtist("Earth, Wind & Fire"))
        assertEquals("Artist A", LocalHomeSections.leadArtist("Artist A; Artist B"))
        assertNull(LocalHomeSections.leadArtist("<unknown>"))
        assertNull(LocalHomeSections.leadArtist("  "))
        assertEquals("daft punk", LocalHomeSections.artistKey("Daft   Punk"))
    }
}
