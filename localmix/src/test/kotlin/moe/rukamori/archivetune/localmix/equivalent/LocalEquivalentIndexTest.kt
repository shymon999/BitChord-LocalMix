/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.equivalent

import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.LocalRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEquivalentIndexTest {
    private fun local(
        id: String,
        title: String,
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        seconds: Double? = 200.0,
    ) = LocalTrack(id, LocalRecording(title, artists, album, seconds?.let { (it * 1000).toLong() }))

    private fun cat(
        title: String,
        artists: List<String> = listOf("Artist"),
        album: String? = "Album",
        seconds: Int? = 200,
    ) = CatalogCandidate("yt", title, artists, album, seconds)

    private fun index(vararg tracks: LocalTrack) = LocalEquivalentIndex(tracks.toList())

    @Test fun exactMatchIsFound() {
        val idx = index(local("1", "Song"), local("2", "Other Song"))
        assertEquals("1", idx.find(cat("Song"))?.songId)
        assertEquals(2, idx.size)
    }

    @Test fun punctuationAndCaseDoNotMatter() {
        assertEquals("1", index(local("1", "Don't Stop Me Now")).find(cat("DONT STOP ME NOW!"))?.songId)
    }

    @Test fun remixIsNotTheOriginal() {
        assertNull(index(local("1", "Song")).find(cat("Song (Axwell Remix)")))
        assertNull(index(local("1", "Song (Axwell Remix)")).find(cat("Song")))
    }

    @Test fun differentRemixerIsRejected() {
        assertNull(index(local("1", "Song (Axwell Remix)")).find(cat("Song (Tiesto Remix)")))
    }

    @Test fun sameRemixIsFound() {
        assertEquals("1", index(local("1", "Song (Axwell Remix)")).find(cat("Song - Axwell Remix"))?.songId)
    }

    @Test fun liveIsRejected() {
        assertNull(index(local("1", "Song")).find(cat("Song (Live at Wembley)")))
        assertNull(index(local("1", "Song (Live)")).find(cat("Song")))
    }

    @Test fun spedUpIsRejected() {
        assertNull(index(local("1", "Song")).find(cat("Song (Sped Up)")))
        assertNull(index(local("1", "Song (Sped Up)")).find(cat("Song (Slowed + Reverb)")))
    }

    @Test fun acousticIsRejected() {
        assertNull(index(local("1", "Song")).find(cat("Song (Acoustic)")))
    }

    @Test fun sameTitleOtherArtistIsRejected() {
        assertNull(index(local("1", "Song")).find(cat("Song", artists = listOf("Somebody Else"))))
    }

    @Test fun durationOffByEightSecondsIsRejected() {
        val idx = index(local("1", "Song", seconds = 208.0))
        assertNull(idx.find(cat("Song", seconds = 200)))
        assertNull(idx.find(cat("Song", seconds = 216)))
    }

    @Test fun smallDurationDifferenceIsAccepted() {
        assertEquals("1", index(local("1", "Song", seconds = 201.6)).find(cat("Song", seconds = 200))?.songId)
    }

    @Test fun missingDurationIsRejected() {
        assertNull(index(local("1", "Song", seconds = null)).find(cat("Song")))
        assertNull(index(local("1", "Song")).find(cat("Song", seconds = null)))
        assertNull(index(local("1", "Song", seconds = 0.0)).find(cat("Song")))
    }

    @Test fun featuredArtistInTitle() {
        val idx = index(local("1", "Song (feat. Guest)"))
        assertEquals("1", idx.find(cat("Song", artists = listOf("Artist", "Guest")))?.songId)
        assertEquals("1", idx.find(cat("Song feat. Guest"))?.songId)
        assertEquals("2", index(local("2", "Song", artists = listOf("Artist feat. Guest"))).find(cat("Song (feat. Guest)"))?.songId)
    }

    @Test fun topicSuffixIsIgnored() {
        assertEquals("1", index(local("1", "Song")).find(cat("Song", artists = listOf("Artist - Topic")))?.songId)
    }

    @Test fun diacriticsAreFolded() {
        val idx = index(local("1", "Café del Mar", artists = listOf("Energía")), local("2", "Żółć", artists = listOf("Łukasz")))
        assertEquals("1", idx.find(cat("Cafe Del Mar", artists = listOf("Energia")))?.songId)
        assertEquals("2", idx.find(cat("Zolc", artists = listOf("Lukasz")))?.songId)
    }

    @Test fun twoDifferentFilesAreAmbiguous() {
        val idx = index(local("1", "Song", seconds = 199.0), local("2", "Song", seconds = 202.5))
        assertNull(idx.find(cat("Song", seconds = 200)))
    }

    @Test fun differentVersionInLibraryDoesNotBlockTheRightOne() {
        val idx = index(local("1", "Song (Live)"), local("2", "Song"), local("3", "Song (Remix)"))
        assertEquals("2", idx.find(cat("Song"))?.songId)
    }

    @Test fun duplicatesOfOneRecordingReturnLowestSongId() {
        val idx = index(local("b", "Song", seconds = 200.4), local("a", "Song", album = "Greatest Hits", seconds = 201.0), local("c", "Song", seconds = 199.8))
        assertEquals("a", idx.find(cat("Song"))?.songId)
    }

    @Test fun noTitleBucketReturnsNull() {
        assertNull(index(local("1", "Song")).find(cat("Completely Different")))
        assertNull(LocalEquivalentIndex(emptyList()).find(cat("Song")))
    }

    @Test fun manyTracksAreFast() {
        val tracks = (0 until 5000).map { local("id$it", "Track Title $it", artists = listOf("Artist ${it % 400}"), seconds = 150.0 + it % 120) }
        val start = System.nanoTime()
        val idx = LocalEquivalentIndex(tracks)
        var hits = 0
        for (i in 0 until 1000) {
            val n = i * 5
            if (idx.find(cat("Track Title $n", artists = listOf("Artist ${n % 400}"), seconds = 150 + n % 120)) != null) hits++
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(1000, hits)
        assertTrue("took $elapsedMs ms", elapsedMs < 1_000)
    }
}
