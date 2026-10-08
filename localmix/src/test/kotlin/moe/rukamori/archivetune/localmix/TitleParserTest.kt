/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleParserTest {
    @Test fun foldRemovesDiacriticsPunctuationAndCase() {
        assertEquals("mya", TextNormalizer.fold("MýA"))
        assertEquals("silka", TextNormalizer.fold("Siłka"))
        assertEquals("baby one more time", TextNormalizer.fold("...Baby One More Time"))
        assertEquals("lil kim", TextNormalizer.fold("Lil' Kim"))
        assertEquals("p nk", TextNormalizer.fold("P!NK"))
    }

    @Test fun symbolOnlyNamesStillMatchThemselves() {
        assertEquals(1.0, TextNormalizer.similarity("!!!", "!!!"), 0.0)
        assertEquals(1.0, TextNormalizer.similarity("+/-", " +/- "), 0.0)
        assertEquals(0.0, TextNormalizer.similarity("!!!", "???"), 0.0)
        assertEquals("!!!", TitleParser.parse("!!!").baseTitle)
    }

    @Test fun marksAreStrippedOnlyAfterLatinGreekAndCyrillicLetters() {
        assertEquals("cafe", TextNormalizer.fold("Café"))
        assertEquals("mya", TextNormalizer.fold("MýA"))
        // Japanese dakuten and handakuten change the letter and must survive folding.
        assertTrue(TextNormalizer.fold("が") != TextNormalizer.fold("か"))
        assertTrue(TextNormalizer.fold("ぱ") != TextNormalizer.fold("は"))
        assertEquals(TextNormalizer.fold("が"), TextNormalizer.fold("が"))
    }

    @Test fun similarityOfEmptyTextIsZero() {
        assertEquals(0.0, TextNormalizer.similarity("", ""), 0.0)
        assertEquals(1.0, TextNormalizer.similarity("Wake Me Up", "wake me up!"), 0.0)
    }

    @Test fun radioEditIsSoftAndKeepsBaseTitle() {
        val p = TitleParser.parse("Levels (Radio Edit)")
        assertEquals("levels", p.baseTitle)
        assertEquals(setOf(VersionFlag.RADIO_EDIT), p.version.flags)
        assertTrue(p.version.hardFlags.isEmpty())
    }

    @Test fun originalRadioEditAndRadioVersionAreRadioEdits() {
        assertEquals(setOf(VersionFlag.RADIO_EDIT), TitleParser.parse("Silhouettes (Original Radio Edit)").version.flags)
        assertEquals(setOf(VersionFlag.RADIO_EDIT), TitleParser.parse("Around the World (La La La La La) [Radio Version]").version.flags)
    }

    @Test fun remixWithRemixerAndRadioEditInOneBracket() {
        val p = TitleParser.parse("Missing (Todd Terry Remix / Radio Edit)")
        assertEquals("missing", p.baseTitle)
        assertEquals("todd terry", p.version.remixer)
        assertEquals(setOf(VersionFlag.REMIX, VersionFlag.RADIO_EDIT), p.version.flags)
    }

    @Test fun catalogWritesTheSameRemixWithoutSlash() {
        val p = TitleParser.parse("Missing (Todd Terry Remix Radio Edit)")
        assertEquals("todd terry", p.version.remixer)
        assertEquals(setOf(VersionFlag.REMIX, VersionFlag.RADIO_EDIT), p.version.flags)
    }

    @Test fun dashSuffixIsReadAsVersion() {
        val p = TitleParser.parse("Missing - Todd Terry Remix")
        assertEquals("missing", p.baseTitle)
        assertEquals("todd terry", p.version.remixer)
    }

    @Test fun mixAndRemixOfTheSameRemixerAreCompatible() {
        val a = TitleParser.parse("In My Mind (Axwell Mix)").version
        val b = TitleParser.parse("In My Mind (Axwell Remix)").version
        assertEquals("axwell", a.remixer)
        assertEquals(VersionCompat.SAME, TitleParser.compare(a, b))
    }

    @Test fun differentRemixersAreHardDifferent() {
        val a = TitleParser.parse("Miracle (Hardwell Remix)").version
        val b = TitleParser.parse("Miracle (David Guetta Remix)").version
        assertEquals(VersionCompat.HARD_DIFFERENCE, TitleParser.compare(a, b))
    }

    @Test fun possessiveRemixerNameIsNormalised() {
        assertEquals("axwell", TitleParser.parse("X (Axwell's Remix)").version.remixer)
    }

    @Test fun featuredArtistsAreExtractedFromTitle() {
        val p = TitleParser.parse("Titanium (feat. Sia)")
        assertEquals("titanium", p.baseTitle)
        assertEquals(listOf("Sia"), p.featured)
        assertTrue(p.version.flags.isEmpty())
    }

    @Test fun soundtrackBracketIsNeitherVersionNorResidual() {
        val p = TitleParser.parse("Lady Marmalade (From \"Moulin Rouge\" Soundtrack)")
        assertEquals("lady marmalade", p.baseTitle)
        assertTrue(p.version.flags.isEmpty())
        assertTrue(p.residual.isEmpty())
    }

    @Test fun liveAndAcousticAreHardAndNotRemixes() {
        val live = TitleParser.parse("Song (Live at Wembley)").version
        val acoustic = TitleParser.parse("Song (Acoustic Version)").version
        assertEquals(setOf(VersionFlag.LIVE), live.flags)
        assertEquals(setOf(VersionFlag.ACOUSTIC), acoustic.flags)
        assertEquals(VersionCompat.HARD_DIFFERENCE, TitleParser.compare(VersionDescriptor.Plain, live))
        assertEquals(VersionCompat.HARD_DIFFERENCE, TitleParser.compare(VersionDescriptor.Plain, acoustic))
    }

    @Test fun originalMixAndAlbumVersionAreNotVersions() {
        assertTrue(TitleParser.parse("Song (Original Mix)").version.flags.isEmpty())
        assertTrue(TitleParser.parse("Song (Album Version)").version.flags.isEmpty())
        assertTrue(TitleParser.parse("Song (Explicit)").version.flags.isEmpty())
    }

    @Test fun extendedAndClubMixAreHardButNotRemixes() {
        assertEquals(setOf(VersionFlag.EXTENDED), TitleParser.parse("Feel So Close (Extended Mix)").version.flags)
        assertEquals(setOf(VersionFlag.CLUB_MIX), TitleParser.parse("Missing (Club Mix)").version.flags)
        assertEquals(setOf(VersionFlag.CLUB_MIX, VersionFlag.REMIX), TitleParser.parse("Missing (Todd Terry Club Mix)").version.flags)
        assertEquals(
            VersionCompat.HARD_DIFFERENCE,
            TitleParser.compare(TitleParser.parse("X (Radio Edit)").version, TitleParser.parse("X (Extended Mix)").version),
        )
    }

    @Test fun karaokeAndTributeMarkersMeanCover() {
        val k = TitleParser.parse("Sweet Nothing (Karaoke Version) [Originally Performed By Calvin Harris]").version
        assertTrue(VersionFlag.KARAOKE in k.flags && VersionFlag.COVER in k.flags)
        assertTrue(VersionFlag.COVER in TitleParser.parse("Wake Me Up (A Tribute to Avicii)").version.flags)
        assertTrue(VersionFlag.COVER in TitleParser.parse("X (As Made Famous by Y)").version.flags)
        assertTrue(VersionFlag.MODIFIED in TitleParser.parse("Levels (Sped Up)").version.flags)
    }

    @Test fun remasterIsSoft() {
        val p = TitleParser.parse("Song - 2011 Remaster").version
        assertEquals(setOf(VersionFlag.REMASTER), p.flags)
        assertEquals(VersionCompat.SOFT_DIFFERENCE, TitleParser.compare(VersionDescriptor.Plain, p))
    }

    @Test fun partSubtitleStaysInTheTitle() {
        assertEquals("song part 1", TitleParser.parse("Song - Part 1").baseTitle)
    }

    @Test fun wordsInsideTheTitleAreNotVersions() {
        val p = TitleParser.parse("Live and Let Die")
        assertEquals("live and let die", p.baseTitle)
        assertTrue(p.version.flags.isEmpty())
        assertNull(TitleParser.parseVersionText("Part 2"))
    }

    @Test fun sessionAndAlternateTakesAreHardDifferent() {
        for (t in listOf("Song (Original Session)", "Song (Alternate Version)", "Song (Stripped)", "Song (Piano Version)", "Song (Orchestral)")) {
            assertEquals(t, setOf(VersionFlag.ALTERNATE), TitleParser.parse(t).version.flags)
            assertEquals(t, VersionCompat.HARD_DIFFERENCE, TitleParser.compare(VersionDescriptor.Plain, TitleParser.parse(t).version))
        }
    }

    @Test fun featuredArtistWithoutBracketsIsExtractedFromTheTitle() {
        val p = TitleParser.parse("Work from Home ft. Ty Dolla \$ign")
        assertEquals("work from home", p.baseTitle)
        assertEquals(listOf("Ty Dolla \$ign"), p.featured)
    }

    @Test fun liveIsRecognisedOnlyAsAVersionMarker() {
        for (t in listOf("Song (Live)", "Song (Live at Wembley)", "Song (Live in Mexico City)", "Song (Acoustic Live)", "Song - Live")) {
            assertTrue(t, VersionFlag.LIVE in TitleParser.parse(t).version.flags)
        }
        for (t in listOf("Song (A Life We Can Live)", "Song (Live and Let Die)", "Alive (Remastered)")) {
            assertFalse(t, VersionFlag.LIVE in TitleParser.parse(t).version.flags)
        }
    }

    @Test fun vocalAndFullVersionsAreTheStandardRecording() {
        assertTrue(TitleParser.parse("Song (Full Vocal Version)").version.flags.isEmpty())
        assertEquals(setOf(VersionFlag.DEMO), TitleParser.parse("Song (Demonstration Version)").version.flags)
    }

    @Test fun realLibraryOddities() {
        assertEquals("hmi", TitleParser.parse("A Little Respect (HMI Redux)").version.remixer)
        assertEquals("phones", TitleParser.parse("Ooh La La (Phones Re-edit)").version.remixer)
        assertEquals("disclosure", TitleParser.parse("Talk (Disclosure VIP)").version.remixer)
        assertEquals("fantasia", TitleParser.parse("Cantaloop (Flip Fantasia)").version.remixer)
        assertEquals("axwell", TitleParser.parse("Resurrection (Axwell's Recut Club Version)").version.remixer)
        // shortened cuts are soft differences, not remixes
        for (t in listOf("Blue (Video Edit)", "Song (Single Version)", "Song (Short Edit)")) {
            assertEquals(t, setOf(VersionFlag.RADIO_EDIT), TitleParser.parse(t).version.flags)
        }
        assertTrue(TitleParser.parse("Erase Me (Main)").version.flags.isEmpty())
        assertTrue(TitleParser.parse("Erase Me (Main)").residual.isEmpty())
        // subtitles in brackets stay part of the title identity, as residual text
        val sub = TitleParser.parse("Only Girl (In The World)")
        assertEquals("only girl", sub.baseTitle)
        assertEquals(listOf("in the world"), sub.residual)
    }

    @Test fun artistSplittingMatchesBothSides() {
        assertEquals(listOf("Calvin Harris", "Ellie Goulding"), TitleParser.splitArtists(listOf("Calvin Harris, Ellie Goulding")))
        assertEquals(listOf("Ivan Gough"), TitleParser.splitArtists(listOf("Ivan Gough - Topic")))
        assertEquals(TitleParser.splitArtists(listOf("Florence + The Machine")), TitleParser.splitArtists(listOf("Florence & The Machine")))
        assertEquals(2, TitleParser.splitArtists(listOf("A x B")).size)
        assertFalse(TitleParser.splitArtists(listOf("")).isNotEmpty())
    }
}
