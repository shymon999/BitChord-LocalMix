package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.localmix.match.LocalHit
import com.music.bitchord.localmix.match.LocalMatchEntry
import com.music.bitchord.ui.offlineArtistSongs
import moe.rukamori.archivetune.localmix.equivalent.MatchDoubt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineArtistSongsTest {
    private fun file(title: String, artist: String, path: String = "/Music/$title.opus", id: String = title) =
        Song(id, title, artist, null, localUri = "content://media/$id", localPath = path)

    private fun match(file: Song, artist: String, title: String = file.title, path: String? = file.localPath) =
        LocalMatchEntry("catalogue-${file.videoId}", LocalHit(file.localUri!!, path, title, artist, emptySet()), null, false)

    private fun page(files: List<Song>, matches: List<LocalMatchEntry> = emptyList(), downloaded: List<Song> = emptyList(), name: String = "Joel Corry") =
        offlineArtistSongs("UC-joel", name, downloaded, files, matches)?.second

    @Test
    fun `explicit filename restores the lead artist lost from tags`() {
        val head = file("Head & Heart", "MNEK", "/Music/Joel Corry/Album/Joel Corry, MNEK - Head & Heart.opus")
        val bed = file("BED", "David Guetta", "/Music/Joel Corry/BED/Joel Corry, RAYE, David Guetta - BED.opus")
        assertEquals(listOf("BED", "Head & Heart"), page(listOf(head, bed))!!.map { it.title })
        assertEquals("MNEK", head.artist)
        assertEquals(head.localUri, page(listOf(head))!!.single().localUri)
    }

    @Test
    fun `catalogue metadata finds a collaboration with incomplete file tags`() {
        val bed = file("Unknown title", "David Guetta")
        val result = page(listOf(bed), listOf(match(bed, "Joel Corry, RAYE, David Guetta", "BED")))!!.single()
        assertEquals("BED", result.title)
        assertEquals(bed.localUri, result.localUri)
        assertEquals(bed.videoId, result.videoId)
    }

    @Test
    fun `saved match cannot claim a reused URI pointing at another path`() {
        val foreign = file("Foreign track", "Other Artist")
        assertNull(page(listOf(foreign), listOf(match(foreign, "Joel Corry", path = "/Old/file.opus"))))
    }

    @Test
    fun `a missing file in the cache is not offered offline`() {
        val gone = file("BED", "David Guetta")
        assertNull(page(emptyList(), listOf(match(gone, "Joel Corry"))))
    }

    @Test
    fun `unconfirmed uncertain catalogue credit does not assign an artist`() {
        val other = file("BED", "David Guetta")
        val uncertain = match(other, "Joel Corry").let {
            it.copy(hit = it.hit.copy(doubts = setOf(MatchDoubt.ARTISTS_DIFFER)))
        }
        assertNull(page(listOf(other), listOf(uncertain)))
        assertEquals(1, page(listOf(other), listOf(uncertain.copy(confirmed = true)))!!.size)
    }

    @Test
    fun `download and multiple catalogue aliases of one file appear once`() {
        val local = file("Head & Heart", "MNEK")
        val official = local.copy(videoId = "official", artist = "Joel Corry, MNEK", artistId = "UC-joel")
        val hit = match(local, "Joel Corry, MNEK")
        val result = page(listOf(local), listOf(hit, hit.copy(videoId = "alias")), listOf(official))!!
        assertEquals(listOf("official"), result.map { it.videoId })
    }

    @Test
    fun `filename matching compares complete names and ignores folder labels`() {
        val steve = file("Track", "Other Artist", "/Music/Eve/Steve Aoki - Track.mp3")
        val folderOnly = file("Track 2", "Other Artist", "/Music/Eve/Track 2.mp3")
        assertNull(page(listOf(steve, folderOnly), name = "Eve"))
    }

    @Test
    fun `tag credits and title guests remain available`() {
        val lead = file("Sorry", "Joel Corry")
        val guest = file("Song (feat. Joel Corry)", "Other Artist")
        assertEquals(2, page(listOf(lead, guest))!!.size)
    }
}
