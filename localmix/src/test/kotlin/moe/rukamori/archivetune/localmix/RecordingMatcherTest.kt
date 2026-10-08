/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMatcherTest {
    private val matcher = RecordingMatcher()

    private fun local(
        title: String,
        artist: String = "Artist",
        album: String? = "Album",
        seconds: Double? = 200.0,
    ) = LocalRecording(title, listOf(artist), album, seconds?.let { (it * 1000).toLong() })

    private fun cat(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String? = "Album",
        seconds: Int? = 200,
    ) = CatalogCandidate(id, title, listOf(artist), album, seconds)

    private fun MatchDecision.autoId() = (this as MatchDecision.Auto).chosen.candidate.catalogId

    @Test fun exactMatchIsAutomatic() {
        assertEquals("a", matcher.decide(local("Song"), listOf(cat("a", "Song"))).autoId())
    }

    @Test fun noCandidatesIsNotFound() {
        assertEquals(MatchDecision.NotFound(NotFoundReason.NO_CANDIDATES), matcher.decide(local("Song"), emptyList()))
    }

    @Test fun otherArtistWithSameTitleAndLengthIsNeverChosen() {
        val d = matcher.decide(local("Song"), listOf(cat("cover", "Song", artist = "Somebody Else")))
        assertTrue(d.toString(), d !is MatchDecision.Auto)
    }

    @Test fun originalBeatsLive() {
        val d = matcher.decide(local("Song"), listOf(cat("live", "Song (Live)", seconds = 200), cat("studio", "Song")))
        assertEquals("studio", d.autoId())
    }

    @Test fun onlyLiveAvailableIsNotAnAutomaticSubstitute() {
        val d = matcher.decide(local("Song"), listOf(cat("live", "Song (Live at Wembley)")))
        assertEquals(MatchDecision.NotFound(NotFoundReason.ONLY_DIFFERENT_VERSIONS), d)
    }

    @Test fun liveLocalRecordingDoesNotTakeTheStudioVersion() {
        val d = matcher.decide(local("Song (Live)"), listOf(cat("studio", "Song")))
        assertEquals(MatchDecision.NotFound(NotFoundReason.ONLY_DIFFERENT_VERSIONS), d)
    }

    @Test fun acousticAndInstrumentalAreDifferentRecordings() {
        val d = matcher.decide(local("Song"), listOf(cat("ac", "Song (Acoustic)"), cat("in", "Song (Instrumental)")))
        assertTrue(d is MatchDecision.NotFound)
    }

    @Test fun intentionalRemixPicksTheMatchingRemixer() {
        val l = local("Missing (Todd Terry Remix)", seconds = 234.8)
        val d =
            matcher.decide(
                l,
                listOf(
                    cat("orig", "Missing", seconds = 248),
                    cat("other", "Missing (Ananda Project Remix)", seconds = 235),
                    cat("tt", "Missing (Todd Terry Remix)", seconds = 235),
                ),
            )
        assertEquals("tt", d.autoId())
    }

    @Test fun remixRequestedButOnlyOriginalAndOtherRemixAreAvailable() {
        val l = local("Missing (Todd Terry Remix)", seconds = 234.8)
        val d = matcher.decide(l, listOf(cat("orig", "Missing", seconds = 248), cat("other", "Missing (Ananda Project Remix)", seconds = 235)))
        assertTrue(d.toString(), d is MatchDecision.NotFound)
    }

    @Test fun originalRequestedDoesNotTakeARemix() {
        val d = matcher.decide(local("Miracle", seconds = 186.5), listOf(cat("rmx", "Miracle (Hardwell Remix)", seconds = 186)))
        assertTrue(d is MatchDecision.NotFound)
    }

    @Test fun radioEditIsPreferredOverExtendedWhenLocalIsRadioEdit() {
        val l = local("Song (Radio Edit)", seconds = 200.0)
        val d = matcher.decide(l, listOf(cat("ext", "Song (Extended Mix)", seconds = 330), cat("radio", "Song (Radio Edit)", seconds = 201)))
        assertEquals("radio", d.autoId())
    }

    @Test fun extendedLocalDoesNotTakeRadioEdit() {
        val l = local("Song (Extended Mix)", seconds = 330.0)
        val d = matcher.decide(l, listOf(cat("radio", "Song (Radio Edit)", seconds = 200)))
        assertTrue(d is MatchDecision.NotFound)
    }

    @Test fun untaggedLocalAcceptsTheCatalogRadioEditWhenLengthAgrees() {
        // Real case: local "Feel So Close" 202.5 s, catalog only has "(Radio Edit)" 207-208 s and an Extended Mix.
        val l = local("Feel So Close", "Calvin Harris", "18 Months", 202.514)
        val d =
            matcher.decide(
                l,
                listOf(
                    cat("ext", "Feel So Close (Extended Mix)", "Calvin Harris", "Feel So Close", 331),
                    cat("re", "Feel So Close (Radio Edit)", "Calvin Harris", "18 Months", 207),
                ),
            )
        assertEquals("re", d.autoId())
    }

    @Test fun unknownCatalogDurationNeverAutoAccepts() {
        val d = matcher.decide(local("Song"), listOf(cat("a", "Song", seconds = null)))
        assertEquals(ChoiceReason.DURATION_UNKNOWN, (d as MatchDecision.NeedsChoice).reason)
    }

    @Test fun unknownLocalDurationNeverAutoAccepts() {
        val d = matcher.decide(local("Song", seconds = null), listOf(cat("a", "Song")))
        assertEquals(ChoiceReason.DURATION_UNKNOWN, (d as MatchDecision.NeedsChoice).reason)
    }

    @Test fun missingDurationCanBeAllowedExplicitly() {
        val lenient = RecordingMatcher(MatchConfig(allowAutoWithoutDuration = true, autoThreshold = 0.70))
        assertEquals("a", lenient.decide(local("Song"), listOf(cat("a", "Song", seconds = null))).autoId())
    }

    @Test fun missingAlbumOnEitherSideStillAutoWithExactLength() {
        assertEquals("a", matcher.decide(local("Song", album = null), listOf(cat("a", "Song", album = "Whatever"))).autoId())
        assertEquals("a", matcher.decide(local("Song"), listOf(cat("a", "Song", album = null))).autoId())
    }

    @Test fun compilationInsteadOfOriginalAlbumDoesNotRequireAChoice() {
        val d = matcher.decide(local("Song", album = "Real Album"), listOf(cat("a", "Song", album = "Now 99 Hits Compilation")))
        assertEquals("a", d.autoId())
    }

    @Test fun equivalentReleasesAreOneRecording() {
        val d =
            matcher.decide(
                local("Song"),
                listOf(cat("album", "Song", album = "Album", seconds = 200), cat("comp", "Song", album = "Hits", seconds = 201)),
            ) as MatchDecision.Auto
        assertEquals(setOf("album", "comp"), (listOf(d.chosen.candidate.catalogId) + d.chosen.equivalentIds).toSet())
        assertTrue(d.runnerUp == null)
    }

    @Test fun catalogRoundingIsNotADifferenceInLength() {
        val l = local("Song", seconds = 202.514)
        assertEquals("a", matcher.decide(l, listOf(cat("a", "Song", seconds = 202))).autoId())
        assertEquals("a", matcher.decide(l, listOf(cat("a", "Song", seconds = 203))).autoId())
    }

    @Test fun twoDifferentRecordingsWithCloseScoresAskTheUser() {
        val d = matcher.decide(local("Intro", seconds = 200.0), listOf(cat("one", "Intro", seconds = 200), cat("two", "Intro", seconds = 206)))
        d as MatchDecision.NeedsChoice
        assertEquals(ChoiceReason.CLOSE_COMPETITOR, d.reason)
        assertEquals(setOf("one", "two"), d.candidates.map { it.candidate.catalogId }.toSet())
    }

    @Test fun choiceListIsCappedAndBestFirst() {
        val many = (1..9).map { cat("c$it", "Intro", seconds = 200 + it * 4) }
        val d = RecordingMatcher(MatchConfig(autoMargin = 0.9)).decide(local("Intro"), many) as MatchDecision.NeedsChoice
        assertTrue(d.candidates.size <= 5)
        assertEquals(d.candidates.sortedByDescending { it.score }, d.candidates)
    }

    @Test fun sameCatalogIdIsCountedOnce() {
        val a = cat("a", "Song")
        assertEquals("a", matcher.decide(local("Song"), listOf(a, a, a)).autoId())
    }

    @Test fun featuredArtistInTitleCountsAsCredit() {
        val l = LocalRecording("Titanium", listOf("David Guetta, Sia"), "Nothing but the Beat", 245_040)
        val c = CatalogCandidate("t", "Titanium (feat. Sia)", listOf("David Guetta"), "Nothing but the Beat Ultimate", 246)
        assertEquals("t", matcher.decide(l, listOf(c)).autoId())
    }

    @Test fun topicChannelSuffixIsIgnored() {
        val l = LocalRecording("Song", listOf("Ivan Gough"), null, 200_000)
        val c = CatalogCandidate("t", "Song", listOf("Ivan Gough - Topic"), null, 200)
        assertEquals("t", matcher.decide(l, listOf(c)).autoId())
    }

    @Test fun diacriticsAndSeparatorsDoNotBreakArtistMatching() {
        val l = LocalRecording("Lady Marmalade (From \"Moulin Rouge\" Soundtrack)", listOf("Christina Aguilera, Lil' Kim, Mýa, P!NK"), "Moulin Rouge", 264_907)
        val c = CatalogCandidate("m", "Lady Marmalade (From \"Moulin Rouge\" Soundtrack)", listOf("Christina Aguilera", "Lil' Kim", "Mya", "P!nk"), "Moulin Rouge", 265)
        assertEquals("m", matcher.decide(l, listOf(c)).autoId())
    }

    @Test fun karaokeAndTributeUploadsAreIgnored() {
        val l = local("Sweet Nothing", "Calvin Harris", "18 Months", 213.994)
        val d =
            matcher.decide(
                l,
                listOf(
                    cat("k", "Sweet Nothing (Karaoke Version)", "Ameritz Top Tracks", seconds = 214),
                    cat("t", "Sweet Nothing (Tribute to Calvin Harris)", "Party Hit Kings", seconds = 214),
                    cat("ok", "Sweet Nothing (feat. Florence Welch)", "Calvin Harris", "18 Months", 213),
                ),
            )
        assertEquals("ok", d.autoId())
    }

    @Test fun aSimilarButDifferentTitleIsNeverAutomatic() {
        val d = matcher.decide(local("Levels"), listOf(cat("x", "Level")))
        assertEquals(ChoiceReason.TITLE_NOT_EXACT, (d as MatchDecision.NeedsChoice).reason)
    }

    @Test fun subtitleInBracketsOnOneSideStillMatches() {
        assertEquals("a", matcher.decide(local("Only Girl (In The World)"), listOf(cat("a", "Only Girl"))).autoId())
        assertEquals("a", matcher.decide(local("Only Girl"), listOf(cat("a", "Only Girl (In The World)"))).autoId())
    }

    @Test fun symbolOnlyArtistAndTitleMatchAutomatically() {
        val l = LocalRecording("Hum", listOf("+/-"), "Album", 200_000)
        assertEquals("p", matcher.decide(l, listOf(CatalogCandidate("p", "Hum", listOf("+/-"), "Album", 200))).autoId())
        val t = LocalRecording("!!!", listOf("A"), "Album", 200_000)
        assertEquals("t", matcher.decide(t, listOf(CatalogCandidate("t", "!!!", listOf("A"), "Album", 200))).autoId())
    }

    @Test fun artistMissingFromCatalogResultIsNotAuto() {
        val d = matcher.decide(local("Song"), listOf(CatalogCandidate("x", "Song", emptyList(), "Album", 200)))
        assertTrue(d !is MatchDecision.Auto)
    }

    @Test fun hardVersionSubtypesCannotSubstituteEachOther() {
        for ((a, b) in listOf(
            "Sped Up" to "Slowed", "Sped Up" to "Reverb", "Piano Version" to "Orchestral Version",
            "Alternate Take 1" to "Alternate Take 2", "Live at Wembley" to "Live at Madison Square Garden",
        )) {
            val result = matcher.decide(local("Song ($a)"), listOf(cat("wrong", "Song ($b)")))
            assertTrue("$a / $b: $result", result is MatchDecision.NotFound)
        }
    }

    @Test fun missingRemixerOrLiveIdentityNeedsConfirmation() {
        for ((a, b) in listOf("Todd Terry Remix" to "Remix", "Remix" to "Todd Terry Remix", "Live at Wembley" to "Live", "Acoustic Remix" to "Acoustic Remix")) {
            val result = matcher.decide(local("Song ($a)"), listOf(cat("uncertain", "Song ($b)"))) as MatchDecision.NeedsChoice
            assertEquals(ChoiceReason.VERSION_UNVERIFIED, result.reason)
        }
    }

    @Test fun similarRemixerNamesAreNotTheSameRemixer() {
        val result = matcher.decide(local("Song (Purple Remix)"), listOf(cat("wrong", "Song (Deep Purple Remix)")))
        assertTrue(result is MatchDecision.NotFound)
    }

    @Test fun acousticRemixRetainsItsRemixerIdentity() {
        val result = matcher.decide(local("Song (Axwell Acoustic Remix)"), listOf(cat("wrong", "Song (Acoustic Version)")))
        assertTrue(result is MatchDecision.NotFound)
        assertEquals("right", matcher.decide(local("Song (Axwell Acoustic Remix)"), listOf(cat("right", "Song (Axwell Acoustic Remix)"))).autoId())
    }

    @Test fun differentBracketSubtitlesAreNotAutomaticOrEquivalent() {
        val result = matcher.decide(local("Song (Part 1)"), listOf(cat("wrong", "Song (Part 2)"))) as MatchDecision.NeedsChoice
        assertEquals(ChoiceReason.TITLE_NOT_EXACT, result.reason)
        val competing = matcher.decide(local("Song (Part 1)"), listOf(cat("right", "Song (Part 1)"), cat("wrong", "Song (Part 2)"))) as MatchDecision.NeedsChoice
        assertEquals(2, competing.candidates.size)
        assertTrue(competing.candidates.all { it.equivalentIds.isEmpty() })
        val remixes = matcher.decide(local("Song (Axwell Remix / Part 1)"), listOf(cat("wrong", "Song (Axwell Remix / Part 2)"))) as MatchDecision.NeedsChoice
        assertEquals(ChoiceReason.TITLE_NOT_EXACT, remixes.reason)
        val missingPart = matcher.decide(local("Song (Part 2)"), listOf(cat("uncertain", "Song"))) as MatchDecision.NeedsChoice
        assertEquals(ChoiceReason.TITLE_NOT_EXACT, missingPart.reason)
    }

    @Test fun detailedMatchingVersionsRemainAutomatic() {
        for (v in listOf("Sped Up", "Piano Version", "Alternate Take 2", "Live at Wembley", "Todd Terry Remix")) {
            assertEquals(v, "right", matcher.decide(local("Song ($v)"), listOf(cat("right", "Song ($v)"))).autoId())
        }
    }

    @Test fun multipleVersionGroupsDoNotEraseAnEarlierModifier() {
        val result = matcher.decide(local("Song (Sped Up) (Reverb)"), listOf(cat("wrong", "Song (Slowed) (Reverb)")))
        assertTrue(result is MatchDecision.NotFound)
        assertEquals("right", matcher.decide(local("Song (Sped Up) (Reverb)"), listOf(cat("right", "Song (Sped Up Reverb)"))).autoId())
    }

    @Test fun contradictoryRemixerGroupsNeedConfirmation() {
        val result = matcher.decide(local("Song (Axwell Remix) (Hardwell Remix)"), listOf(cat("wrong", "Song (Axwell Remix)"))) as MatchDecision.NeedsChoice
        assertEquals(ChoiceReason.VERSION_UNVERIFIED, result.reason)
    }

    @Test fun partOfABandNameDoesNotIdentifyTheBand() {
        for ((band, fragment) in listOf("AC/DC" to "AC", "Hall & Oates" to "Hall", "Florence + The Machine" to "Florence")) {
            val result = matcher.decide(local("Song", band), listOf(cat("wrong", "Song", fragment)))
            assertTrue("$band / $fragment: $result", result !is MatchDecision.Auto)
            assertEquals("right", matcher.decide(local("Song", band), listOf(cat("right", "Song", band))).autoId())
        }
    }

    @Test fun optionalGuestCreditsDoNotBecomeThePrimaryGroup() {
        assertEquals("right", matcher.decide(local("Song", "Artist feat. Guest"), listOf(cat("right", "Song", "Artist"))).autoId())
        assertEquals("right", matcher.decide(local("Song", "Artist, Guest"), listOf(cat("right", "Song", "Artist"))).autoId())
    }
}
