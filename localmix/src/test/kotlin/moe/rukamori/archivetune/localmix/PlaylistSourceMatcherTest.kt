package moe.rukamori.archivetune.localmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSourceMatcherTest {
    private val ext = "com.android.externalstorage.documents"

    private fun src(uri: String, docId: String? = null, path: String? = null, authority: String = ext) =
        PlaylistSource(uri, docId?.let { "$authority|$it" }, path)

    private val club = PlaylistSourceMatcher.Known(
        "club",
        "Club",
        src("content://$ext/document/primary%3AMusic%2FClub.m3u8", "primary:Music/Club.m3u8", "external_primary:Music/Club.m3u8"),
    )
    private val clubOld = PlaylistSourceMatcher.Known(
        "club-old",
        "Club",
        src("content://$ext/document/primary%3AOld%2FClub.m3u8", "primary:Old/Club.m3u8", "external_primary:Old/Club.m3u8"),
    )

    @Test fun sameUriWins() {
        val found = PlaylistSourceMatcher.find(club.source, "Renamed", listOf(clubOld, club))
        assertEquals("club", found?.id)
    }

    @Test fun folderGrantFindsTheFilePickedBefore() {
        val viaTree = src(
            "content://$ext/tree/primary%3AMusic/document/primary%3AMusic%2FClub.m3u8",
            "primary:Music/Club.m3u8",
            "external_primary:Music/Club.m3u8",
        )
        assertEquals("club", PlaylistSourceMatcher.find(viaTree, "Club", listOf(clubOld, club))?.id)
    }

    @Test fun pathMatchesAcrossProviders() {
        val viaDownloads = src("content://downloads/document/raw%3A...", "raw:/storage/emulated/0/Music/Club.m3u8", "external_primary:music/club.m3u8", "com.android.providers.downloads.documents")
        assertEquals("club", PlaylistSourceMatcher.find(viaDownloads, "Something", listOf(club))?.id)
    }

    @Test fun sameNameElsewhereIsAnotherPlaylist() {
        val other = src("content://$ext/document/primary%3ANew%2FClub.m3u8", "primary:New/Club.m3u8", "external_primary:New/Club.m3u8")
        assertNull(PlaylistSourceMatcher.find(other, "Club", listOf(club)))
    }

    @Test fun sameNameIsAcceptedWhenTheOldFileIsGone() {
        val moved = src("content://$ext/document/primary%3ANew%2FClub.m3u8", "primary:New/Club.m3u8", "external_primary:New/Club.m3u8")
        assertEquals("club", PlaylistSourceMatcher.find(moved, "Club", listOf(club)) { true }?.id)
    }

    @Test fun nameIsAFallbackWhenLocationsAreUnknown() {
        val unknownOld = PlaylistSourceMatcher.Known("x", "Chill", src("content://some.cloud/doc/1"))
        val incoming = src("content://other.cloud/doc/9")
        assertEquals("x", PlaylistSourceMatcher.find(incoming, "chill ", listOf(unknownOld))?.id)
    }

    @Test fun ambiguousNameMatchesNothing() {
        val a = PlaylistSourceMatcher.Known("a", "Chill", src("content://c/1"))
        val b = PlaylistSourceMatcher.Known("b", "Chill", src("content://c/2"))
        assertNull(PlaylistSourceMatcher.find(src("content://d/3"), "Chill", listOf(a, b)))
    }

    @Test fun differentDocumentsOfOneProviderAreDifferentFiles() {
        val a = PlaylistSourceMatcher.Known("a", "Chill", src("content://p/1", "msf:1", authority = "media"))
        assertNull(PlaylistSourceMatcher.find(src("content://p/2", "msf:2", authority = "media"), "Chill", listOf(a)))
    }
}
