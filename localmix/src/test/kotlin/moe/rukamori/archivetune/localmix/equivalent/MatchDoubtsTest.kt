package moe.rukamori.archivetune.localmix.equivalent

import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.LocalRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchDoubtsTest {
    private fun local(
        id: String,
        title: String,
        artists: List<String> = listOf("Artist"),
        ms: Long = 200_500,
    ) = LocalTrack(id, LocalRecording(title, artists, "Album", ms))

    private fun cat(
        title: String,
        artists: List<String> = listOf("Artist"),
        seconds: Int = 200,
    ) = CatalogCandidate("yt", title, artists, "Album", seconds)

    @Test fun exactMatchHasNoDoubts() {
        val m = LocalEquivalentIndex(listOf(local("1", "Song"))).findMatch(cat("Song"))
        assertEquals("1", m?.track?.songId)
        assertFalse(m!!.isUncertain)
    }

    @Test fun findAndFindMatchAgree() {
        val idx = LocalEquivalentIndex(listOf(local("1", "Song"), local("2", "Song (Live)")))
        assertEquals(idx.find(cat("Song"))?.songId, idx.findMatch(cat("Song"))?.track?.songId)
        assertNull(idx.findMatch(cat("Other")))
    }

    @Test fun durationNearTheCapIsFlagged() {
        val m = LocalEquivalentIndex(listOf(local("1", "Song", ms = 202_800))).findMatch(cat("Song"))
        assertEquals("1", m?.track?.songId)
        assertTrue(MatchDoubt.DURATION_GAP in m!!.doubts)
    }

    @Test fun softVersionIsFlagged() {
        val m = LocalEquivalentIndex(listOf(local("1", "Song"))).findMatch(cat("Song (Remastered 2011)"))
        assertTrue(MatchDoubt.SOFT_VERSION in m!!.doubts)
    }

    @Test fun extraCreditsAreFlagged() {
        val m = LocalEquivalentIndex(listOf(local("1", "Song")))
            .findMatch(cat("Song", artists = listOf("Artist", "Guest One", "Guest Two")))
        assertTrue(MatchDoubt.ARTISTS_DIFFER in m!!.doubts)
    }

    @Test fun mayHaveNeedsTitleAndArtistInSubtitle() {
        val idx = LocalEquivalentIndex(listOf(local("1", "Californication", listOf("Red Hot Chili Peppers"))))
        assertTrue(idx.mayHave("Californication", "Song • Red Hot Chili Peppers"))
        assertTrue(idx.mayHave("Californication (Remastered)", "Red Hot Chili Peppers • Californication"))
        assertFalse(idx.mayHave("Californication", "Song • Some Cover Band"))
        assertFalse(idx.mayHave("Scar Tissue", "Red Hot Chili Peppers"))
        // A short artist key must not hit inside another word.
        assertFalse(LocalEquivalentIndex(listOf(local("1", "Song", listOf("A")))).mayHave("Song", "Song • Abba"))
    }
}
