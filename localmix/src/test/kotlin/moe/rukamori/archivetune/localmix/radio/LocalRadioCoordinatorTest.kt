/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.localmix.CatalogSnapshot
import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.CatalogSearchPort
import moe.rukamori.archivetune.localmix.LinkConfirmation
import moe.rukamori.archivetune.localmix.LinkStorePort
import moe.rukamori.archivetune.localmix.LocalAsset
import moe.rukamori.archivetune.localmix.LocalOnlineLink
import moe.rukamori.archivetune.localmix.LocalRecording
import moe.rukamori.archivetune.localmix.MatchPurpose
import moe.rukamori.archivetune.localmix.RecordingResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalRadioCoordinatorTest {
    private val asset = LocalAsset("local:1", LocalRecording("Feel So Close", listOf("Calvin Harris"), "18 Months", 202_514), 1000, 2000)
    private val anchor = OccurrenceRef("occ-1", "pl", 3, "e-1", "local:1")

    private class Player : RadioPlayerPort {
        var generation = 1L
        var currentOccurrence: String? = "occ-1"
        var playbackId: String? = "local-1"
        var revision: String? = null
        var exists = true
        var blendPolls = 0
        var modes = PlayerModes(true, RepeatMode.OFF)
        var remaining = 1
        var acceptCommit = true
        var acceptAppend = true
        val commits = mutableListOf<List<String>>()
        val selectedCommits = mutableListOf<Pair<OccurrenceRef, List<String>>>()
        val appended = mutableListOf<List<String>>()
        var commitModes: PlayerModes? = null
        var lastExpectedGeneration = -1L

        var viewCalls = 0
        var bumpGenerationAfterViews = -1

        override suspend fun view(anchorOccurrenceId: String): PlayerQueueView {
            val v = PlayerQueueView(generation, currentOccurrence, playbackId, revision, exists, blendPolls-- > 0, modes, remaining)
            if (++viewCalls == bumpGenerationAfterViews) generation++
            return v
        }

        override suspend fun commitCurrent(sessionId: String, items: List<RadioItem>, modes: PlayerModes, context: RadioRequestContext, stillValid: () -> Boolean): Long? {
            val expectedGeneration = context.queueGeneration
            lastExpectedGeneration = expectedGeneration
            if (acceptCommit && stillValid() && expectedGeneration == generation) {
                commits += items.map { it.catalogId }
                commitModes = modes
            }
            return generation.takeIf { acceptCommit && stillValid() && expectedGeneration == generation }
        }

        override suspend fun commitSelected(sessionId: String, selected: OccurrenceRef, items: List<RadioItem>, modes: PlayerModes, context: RadioRequestContext, stillValid: () -> Boolean): Long? {
            if (!acceptCommit || !stillValid()) return null
            selectedCommits += selected to items.map { it.catalogId }
            generation++
            return generation
        }

        override suspend fun appendTail(sessionId: String, items: List<RadioItem>, expectedGeneration: Long): Boolean {
            val ok = acceptAppend && expectedGeneration == generation
            if (ok) appended += items.map { it.catalogId }
            return ok
        }
    }

    private class Source : RadioSourcePort {
        val prepareCalls = mutableListOf<String>()
        val nextCalls = mutableListOf<String>()
        var prepare: suspend (String) -> RadioPage = { RadioPage(listOf(RadioItem("seed"), RadioItem("r1"), RadioItem("r2")), "c1") }
        var next: suspend (String) -> RadioPage = { RadioPage(listOf(RadioItem("r3")), null) }

        override suspend fun prepare(seedCatalogId: String): RadioPage {
            prepareCalls += seedCatalogId
            return prepare.invoke(seedCatalogId)
        }

        override suspend fun next(continuation: String): RadioPage {
            nextCalls += continuation
            return next.invoke(continuation)
        }
    }

    private class Links(var link: LocalOnlineLink? = null) : LinkStorePort {
        override suspend fun find(assetKey: String) = link
        override suspend fun save(link: LocalOnlineLink) {
            this.link = link
        }
        override suspend fun compareAndSet(expected: LocalOnlineLink?, link: LocalOnlineLink): Boolean {
            if (this.link != expected) return false
            save(link)
            return true
        }
    }

    private class Search(var answer: suspend () -> List<CatalogCandidate>) : CatalogSearchPort {
        var calls = 0
        override suspend fun searchSongs(query: String): List<CatalogCandidate> {
            calls++
            return answer()
        }
    }

    private class Env(scope: CoroutineScope, val asset: LocalAsset, config: RadioConfig = RadioConfig()) {
        val player = Player().also { it.revision = asset.revision }
        val source = Source()
        var sourceFactory: () -> RadioSourcePort = {
            object : RadioSourcePort {
                override suspend fun prepare(seedCatalogId: String) = source.prepare(seedCatalogId)
                override suspend fun next(continuation: String) = source.next(continuation)
            }
        }
        val links = Links(LocalOnlineLink(asset.assetKey, asset.revision, "YOUTUBE_MUSIC", "seed", LinkConfirmation.AUTO, MatchPurpose.RADIO_SEED, RecordingResolver.ALGORITHM_VERSION, null, 1))
        val search = Search { emptyList() }
        var n = 0
        val coordinator = LocalRadioCoordinator(scope, RecordingResolver(search, links), { sourceFactory() }, player, config, clock = { 1L }, sessionIds = { "s${++n}" })
    }

    private fun TestScope.env(config: RadioConfig = RadioConfig()) = Env(backgroundScope, asset, config)

    private fun request(action: UserAction = UserAction.CURRENT) = RadioStartRequest(asset, anchor, "local-1", action)

    @Test fun restoredActiveDoesNotResolveOrFetchUntilPagingAndRenewalKeepsSeenAliases() = runTest {
        val e = env()
        e.links.link = null
        e.source.prepare = { RadioPage(listOf(RadioItem("seed-alias"), RadioItem("seen-alias"), RadioItem("new")), "fresh") }
        val saved = PersistedRadio("restored", listOf("seed", "seed-alias"), listOf("seen", "seen-alias"), "expired", false)
        assertTrue(e.coordinator.restoreSession(saved, e.player.generation))
        assertEquals(RadioState.ACTIVE, e.coordinator.status.value.state)
        assertTrue(e.source.prepareCalls.isEmpty())
        assertEquals(0, e.search.calls)
        assertTrue(e.player.commits.isEmpty())
        assertEquals(1, e.coordinator.loadNextPage(force = true))
        assertEquals(listOf("seed"), e.source.prepareCalls)
        assertTrue(e.source.nextCalls.isEmpty())
        assertEquals(listOf(listOf("new")), e.player.appended)
        assertEquals("fresh", e.coordinator.exportSession()?.continuation)
    }

    @Test fun restoredEndedSessionDoesNotFetchAndInvalidLocalSeedIsRejected() = runTest {
        val e = env()
        assertFalse(e.coordinator.restoreSession(PersistedRadio("s", listOf("content://media/1"), emptyList(), null, false), 1))
        assertNull(e.coordinator.status.value.activeSessionId)
        assertTrue(e.coordinator.restoreSession(PersistedRadio("s", listOf("seed"), emptyList(), null, true), 1))
        assertNull(e.coordinator.loadNextPage(force = true))
        assertTrue(e.source.prepareCalls.isEmpty())
    }

    @Test fun restoredOfflineRadioKeepsQueueAndSeenUntilExplicitRetry() = runTest {
        val e = env()
        val saved = PersistedRadio("restored", listOf("seed"), listOf("already"), "expired", false)
        assertTrue(e.coordinator.restoreSession(saved, 1))
        e.source.prepare = { throw RadioSourceException(RadioSourceException.Kind.OFFLINE) }
        assertNull(e.coordinator.loadNextPage(force = true))
        assertEquals("restored", e.coordinator.status.value.activeSessionId)
        assertTrue(e.coordinator.status.value.pagingNeedsRetry)
        assertTrue(e.player.commits.isEmpty() && e.player.appended.isEmpty())
        assertEquals(listOf("already"), e.coordinator.exportSession()?.seenCatalogIds)
        assertNull(e.coordinator.loadNextPage())
        assertEquals(1, e.source.prepareCalls.size)
        e.source.prepare = { RadioPage(listOf(RadioItem("already"), RadioItem("new")), null) }
        assertEquals(1, e.coordinator.loadNextPage(force = true))
        assertEquals(listOf(listOf("new")), e.player.appended)
    }

    @Test fun failedOrStaleAdapterPreparationPreservesCommittedSessionAndNeverTouchesQueue() = runTest {
        val e = env()
        e.coordinator.start(request()).await()
        val saved = e.coordinator.exportSession()
        e.coordinator.rejectPreparation(FailureReason.PERMISSION_LOST)
        assertEquals(RadioState.FAILED, e.coordinator.status.value.state)
        assertEquals(FailureReason.PERMISSION_LOST, e.coordinator.status.value.failure)
        assertEquals("s1", e.coordinator.status.value.activeSessionId)
        assertEquals(saved, e.coordinator.exportSession())
        e.coordinator.stalePreparation(StaleReason.ANCHOR_CHANGED)
        assertEquals(RadioState.STALE, e.coordinator.status.value.state)
        assertEquals(StaleReason.ANCHOR_CHANGED, e.coordinator.status.value.stale)
        assertNull(e.coordinator.status.value.failure)
        assertEquals(saved, e.coordinator.exportSession())
        assertEquals(1, e.player.commits.size)
        assertTrue(e.player.appended.isEmpty())
    }

    @Test fun localTrackBecomesRadioWithoutTouchingThePlayerBeforeCommit() = runTest {
        val e = env()
        val outcome = e.coordinator.start(request()).await()
        assertEquals(RadioOutcome.Active("s1", 2), outcome)
        assertEquals(listOf(listOf("r1", "r2")), e.player.commits)
        assertEquals(PlayerModes(false, RepeatMode.OFF), e.player.commitModes)
        assertEquals(RadioState.ACTIVE, e.coordinator.status.value.state)
        assertEquals(listOf("seed"), e.source.prepareCalls)
        assertEquals(0, e.search.calls)
    }

    @Test fun confirmedSeedAliasesAreExcludedOnEveryPage() = runTest {
        val e = env()
        e.links.link = e.links.link!!.copy(snapshot = CatalogSnapshot("Feel So Close", listOf("Calvin Harris"), null, 203, listOf("seed-alias")))
        e.source.prepare = { RadioPage(listOf(RadioItem("seed-alias"), RadioItem("r1")), "c1") }
        e.source.next = { RadioPage(listOf(RadioItem("seed-alias"), RadioItem("r2")), null) }
        assertEquals(RadioOutcome.Active("s1", 1), e.coordinator.start(request()).await())
        assertEquals(listOf(listOf("r1")), e.player.commits)
        assertEquals(1, e.coordinator.loadNextPage())
        assertEquals(listOf(listOf("r2")), e.player.appended)
    }

    @Test fun selectedRadioStartsTheChosenLocalItemAndNeedsNoQueueMatch() = runTest {
        val e = env()
        e.player.generation = 99
        val res = e.coordinator.start(request(UserAction.SELECTED)).await()
        assertTrue(res is RadioOutcome.Active)
        assertEquals(listOf(anchor to listOf("r1", "r2")), e.player.selectedCommits)
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun needsChoiceWaitsForTheUserAndRemembersTheManualPick() = runTest {
        val e = env()
        e.links.link = null
        e.search.answer = {
            listOf(
                CatalogCandidate("a", "Feel So Close", listOf("Calvin Harris"), "x", 200),
                CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 205),
            )
        }
        val run = e.coordinator.start(request())
        runCurrent()
        assertEquals(RadioState.NEEDS_CHOICE, e.coordinator.status.value.state)
        assertEquals(2, e.coordinator.status.value.candidates.size)
        assertTrue(e.player.commits.isEmpty())
        e.coordinator.choose(CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 205))
        assertTrue(run.await() is RadioOutcome.Active)
        assertEquals(listOf("b"), e.source.prepareCalls)
        assertEquals(LinkConfirmation.MANUAL, e.links.link!!.confirmation)
        assertEquals("b", e.links.link!!.catalogId)
    }

    @Test fun explicitManualRetryBypassesRememberedNoMatchAndWaitsWithoutFetchingOrChangingQueue() = runTest {
        val e = env()
        e.links.link = e.links.link!!.copy(catalogId = null, confirmation = LinkConfirmation.MANUAL)
        assertEquals(RadioOutcome.Failed(FailureReason.NOT_FOUND), e.coordinator.start(request()).await())
        val manual = e.coordinator.startManual(request())
        runCurrent()
        assertEquals(RadioState.NEEDS_CHOICE, e.coordinator.status.value.state)
        assertTrue(e.coordinator.status.value.candidates.isEmpty())
        assertEquals(0, e.search.calls)
        assertTrue(e.player.commits.isEmpty() && e.source.prepareCalls.isEmpty())
        e.coordinator.choose(CatalogCandidate("chosen", "Feel So Close", listOf("Calvin Harris"), null, 203))
        assertTrue(manual.await() is RadioOutcome.Active)
        assertEquals(listOf("chosen"), e.source.prepareCalls)
        assertEquals("chosen", e.links.link?.catalogId)
        assertEquals(LinkConfirmation.MANUAL, e.links.link?.confirmation)
        assertEquals(0, e.search.calls)
    }

    @Test fun explicitManualNoCorrectMatchIsDurableAndNormalStartStillFailsWithoutLookup() = runTest {
        val e = env()
        val manual = e.coordinator.startManual(request())
        runCurrent()
        e.coordinator.choose(null)
        assertEquals(RadioOutcome.Failed(FailureReason.NOT_FOUND), manual.await())
        assertTrue(e.links.link!!.isNoCorrectMatch)
        assertEquals(LinkConfirmation.MANUAL, e.links.link?.confirmation)
        assertEquals(RadioOutcome.Failed(FailureReason.NOT_FOUND), e.coordinator.start(request()).await())
        assertEquals(0, e.search.calls)
        assertTrue(e.player.commits.isEmpty() && e.source.prepareCalls.isEmpty())
    }

    @Test fun explicitManualChoiceCannotWriteOrCommitWhenAnchorMetadataChangedDuringDialog() = runTest {
        val e = env()
        val previousLink = e.links.link
        val manual = e.coordinator.startManual(request())
        runCurrent()
        e.player.revision = "new-recording-revision"
        e.coordinator.choose(CatalogCandidate("chosen", "Feel So Close", listOf("Calvin Harris"), null, 203))
        assertEquals(RadioOutcome.Stale(StaleReason.METADATA_CHANGED), manual.await())
        assertEquals(previousLink, e.links.link)
        assertEquals(0, e.search.calls)
        assertTrue(e.player.commits.isEmpty() && e.source.prepareCalls.isEmpty())
    }

    @Test fun noCorrectMatchFailsWithoutCommitAndIsRemembered() = runTest {
        val e = env()
        e.links.link = null
        e.search.answer = { listOf(CatalogCandidate("a", "Feel So Close", listOf("Calvin Harris"), "x", 200), CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 205)) }
        val run = e.coordinator.start(request())
        runCurrent()
        e.coordinator.choose(null)
        assertEquals(RadioOutcome.Failed(FailureReason.NOT_FOUND), run.await())
        assertTrue(e.player.commits.isEmpty())
        assertTrue(e.links.link!!.isNoCorrectMatch)
    }

    @Test fun closingTheChoiceDialogCancelsAndLeavesThePlayerAlone() = runTest {
        val e = env()
        e.links.link = null
        e.search.answer = { listOf(CatalogCandidate("a", "Feel So Close", listOf("Calvin Harris"), "x", 200), CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 205)) }
        val run = e.coordinator.start(request())
        runCurrent()
        e.coordinator.cancel()
        runCurrent()
        assertTrue(run.isCancelled)
        assertEquals(RadioState.CANCELLED, e.coordinator.status.value.state)
        assertTrue(e.player.commits.isEmpty() && e.source.prepareCalls.isEmpty())
    }

    @Test fun offlineResolutionFailsFastWithoutCommit() = runTest {
        val e = env()
        e.links.link = null
        e.search.answer = { throw moe.rukamori.archivetune.localmix.CatalogSearchException(moe.rukamori.archivetune.localmix.CatalogSearchException.Kind.OFFLINE) }
        assertEquals(RadioOutcome.Failed(FailureReason.OFFLINE), e.coordinator.start(request()).await())
        assertTrue(e.player.commits.isEmpty())
        assertEquals(RadioState.FAILED, e.coordinator.status.value.state)
    }

    @Test fun offlineSourceIsNotRetried() = runTest {
        val e = env()
        e.source.prepare = { throw RadioSourceException(RadioSourceException.Kind.OFFLINE) }
        assertEquals(RadioOutcome.Failed(FailureReason.OFFLINE), e.coordinator.start(request()).await())
        assertEquals(1, e.source.prepareCalls.size)
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun retryableSourceErrorsAreRetriedWithBackoffThenSucceed() = runTest {
        val e = env()
        var calls = 0
        e.source.prepare = { if (++calls < 3) throw RadioSourceException(RadioSourceException.Kind.RETRYABLE) else RadioPage(listOf(RadioItem("r1")), null) }
        val run = e.coordinator.start(request())
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(3, calls)
        assertTrue(run.await() is RadioOutcome.Active)
    }

    @Test fun exhaustedRetriesFailWithoutCommit() = runTest {
        val e = env()
        e.source.prepare = { throw RadioSourceException(RadioSourceException.Kind.RETRYABLE) }
        assertEquals(RadioOutcome.Failed(FailureReason.RETRYABLE), e.coordinator.start(request()).await())
        assertEquals(3, e.source.prepareCalls.size)
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun aHangingSourceTimesOutAsAFailureNotAsCancellation() = runTest {
        val e = env()
        e.source.prepare = { CompletableDeferred<RadioPage>().await() }
        val run = e.coordinator.start(request())
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(RadioOutcome.Failed(FailureReason.TIMEOUT), run.await())
        assertEquals(RadioState.FAILED, e.coordinator.status.value.state)
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun emptyFirstPageNeverCommits() = runTest {
        val e = env()
        e.source.prepare = { RadioPage(listOf(RadioItem("seed"), RadioItem("x", allowed = false)), null) }
        assertEquals(RadioOutcome.Failed(FailureReason.EMPTY_FIRST_PAGE), e.coordinator.start(request()).await())
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun endlessFilteredFirstPagesStopAtTheLimitInsteadOfLooping() = runTest {
        val e = env()
        e.source.prepare = { RadioPage(listOf(RadioItem("x", allowed = false)), "more") }
        e.source.next = { RadioPage(listOf(RadioItem("y", allowed = false)), "more") }
        assertEquals(RadioOutcome.Failed(FailureReason.EMPTY_FIRST_PAGE), e.coordinator.start(request()).await())
        assertEquals(2, e.source.nextCalls.size)
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun userEditWhileFetchingMakesTheResultStale() = runTest {
        val e = env()
        val gate = CompletableDeferred<RadioPage>()
        e.source.prepare = { gate.await() }
        val run = e.coordinator.start(request())
        runCurrent()
        e.player.generation++
        gate.complete(RadioPage(listOf(RadioItem("r1")), null))
        assertEquals(RadioOutcome.Stale(StaleReason.QUEUE_CHANGED), run.await())
        assertTrue(e.player.commits.isEmpty())
        assertEquals(RadioState.STALE, e.coordinator.status.value.state)
    }

    @Test fun aQueueEditBetweenTheGuardAndTheWriteIsRefusedByThePlayer() = runTest {
        val e = env()
        e.player.bumpGenerationAfterViews = 2
        assertEquals(RadioOutcome.Stale(StaleReason.QUEUE_CHANGED), e.coordinator.start(request()).await())
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun anUnexpectedErrorEndsAsFailedNotAsACrashedJob() = runTest {
        val e = env()
        e.source.prepare = { throw IllegalStateException("bug") }
        assertEquals(RadioOutcome.Failed(FailureReason.INTERNAL_ERROR), e.coordinator.start(request()).await())
        assertEquals(RadioState.FAILED, e.coordinator.status.value.state)
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun movingToTheNextOccurrenceWhileFetchingIsStale() = runTest {
        val e = env()
        val gate = CompletableDeferred<RadioPage>()
        e.source.prepare = { gate.await() }
        val run = e.coordinator.start(request())
        runCurrent()
        e.player.currentOccurrence = "occ-2"
        gate.complete(RadioPage(listOf(RadioItem("r1")), null))
        assertEquals(RadioOutcome.Stale(StaleReason.ANCHOR_CHANGED), run.await())
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun fileChangedWhileFetchingIsStale() = runTest {
        val e = env()
        val gate = CompletableDeferred<RadioPage>()
        e.source.prepare = { gate.await() }
        val run = e.coordinator.start(request())
        runCurrent()
        e.player.revision = "other-revision"
        gate.complete(RadioPage(listOf(RadioItem("r1")), null))
        assertEquals(RadioOutcome.Stale(StaleReason.METADATA_CHANGED), run.await())
    }

    @Test fun aNewerRequestSupersedesTheOlderOneAndOnlyItCommits() = runTest {
        val e = env()
        val first = CompletableDeferred<RadioPage>()
        var calls = 0
        e.source.prepare = { if (++calls == 1) first.await() else RadioPage(listOf(RadioItem("new")), null) }
        val old = e.coordinator.start(request())
        runCurrent()
        val newer = e.coordinator.start(request())
        runCurrent()
        first.complete(RadioPage(listOf(RadioItem("old")), null))
        assertTrue(newer.await() is RadioOutcome.Active)
        assertTrue(old.isCancelled)
        assertEquals(listOf(listOf("new")), e.player.commits)
    }

    @Test fun commitWaitsWhileABlendRunsThenProceeds() = runTest {
        val e = env()
        e.player.blendPolls = 3
        val run = e.coordinator.start(request())
        runCurrent()
        assertTrue(e.player.commits.isEmpty())
        advanceTimeBy(1_000)
        assertTrue(run.await() is RadioOutcome.Active)
        assertEquals(1, e.player.commits.size)
    }

    @Test fun aBlendThatNeverEndsEventuallyGivesUpAsStale() = runTest {
        val e = env(RadioConfig(maxDeferPolls = 5))
        e.player.blendPolls = Int.MAX_VALUE
        assertEquals(RadioOutcome.Stale(StaleReason.ANCHOR_CHANGED), e.coordinator.start(request()).await())
        assertTrue(e.player.commits.isEmpty())
    }

    @Test fun aPlayerThatRefusesTheCommitLeavesNoActiveSession() = runTest {
        val e = env()
        e.player.acceptCommit = false
        assertEquals(RadioOutcome.Stale(StaleReason.QUEUE_CHANGED), e.coordinator.start(request()).await())
        assertNull(e.coordinator.loadNextPage(force = true))
    }

    private fun <T> TestScope.launchCall(block: suspend () -> T): kotlinx.coroutines.Deferred<T> = (this as CoroutineScope).async { block() }

    private suspend fun Env.active() {
        coordinator.start(RadioStartRequest(asset, anchor, "local-1", UserAction.CURRENT)).await()
    }

    @Test fun pagingAppendsOnePageWithTheSeedFilteredAndTheBoundaryNotLost() = runTest {
        val e = env()
        e.source.next = { RadioPage(listOf(RadioItem("r2"), RadioItem("r3"), RadioItem("seed")), "c2") }
        e.active()
        e.player.remaining = 1
        assertEquals(1, e.coordinator.loadNextPage())
        assertEquals(listOf(listOf("r3")), e.player.appended)
        assertEquals(listOf("c1"), e.source.nextCalls)
    }

    @Test fun aFirstPageThatIsEmptyAfterFilteringChainsToTheNextPageInsteadOfFailing() = runTest {
        val e = env()
        e.source.prepare = { RadioPage(listOf(RadioItem("seed"), RadioItem("x", allowed = false)), "c1") }
        e.source.next = { RadioPage(listOf(RadioItem("good1"), RadioItem("good2")), "c2") }
        assertEquals(RadioOutcome.Active("s1", 2), e.coordinator.start(request()).await())
        assertEquals(listOf(listOf("good1", "good2")), e.player.commits)
        assertEquals(listOf("c1"), e.source.nextCalls)
    }

    @Test fun aPageTheQueueRefusedIsNotLostAndTheSameContinuationIsFetchedAgain() = runTest {
        val e = env()
        e.source.next = { RadioPage(listOf(RadioItem("r3"), RadioItem("r4")), "c2") }
        e.active()
        e.player.acceptAppend = false
        assertNull(e.coordinator.loadNextPage())
        assertTrue(e.coordinator.status.value.pagingNeedsRetry)
        e.player.acceptAppend = true
        assertEquals(2, e.coordinator.loadNextPage(force = true))
        assertEquals(listOf(listOf("r3", "r4")), e.player.appended)
        assertEquals(listOf("c1", "c1"), e.source.nextCalls)
    }

    @Test fun aFileThatChangedWhileTheChoiceDialogWasOpenIsNotRememberedAsAManualLink() = runTest {
        val e = env()
        e.links.link = null
        e.search.answer = { listOf(CatalogCandidate("a", "Feel So Close", listOf("Calvin Harris"), "x", 200), CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 205)) }
        val run = e.coordinator.start(request())
        runCurrent()
        e.player.revision = "changed-tags"
        e.coordinator.choose(CatalogCandidate("b", "Feel So Close", listOf("Calvin Harris"), "y", 205))
        run.await()
        assertNull(e.links.link)
    }

    @Test fun pagingWaitsUntilTheQueueIsLowAndOnlyWithRepeatOff() = runTest {
        val e = env()
        e.active()
        e.player.remaining = 20
        assertNull(e.coordinator.loadNextPage())
        e.player.remaining = 1
        e.player.modes = PlayerModes(false, RepeatMode.ONE)
        assertNull(e.coordinator.loadNextPage())
        assertNull(e.coordinator.loadNextPage(force = true))
        e.player.modes = PlayerModes(false, RepeatMode.OFF)
        assertEquals(1, e.coordinator.loadNextPage())
    }

    @Test fun onlyOneNextPageJobRunsPerSession() = runTest {
        val e = env()
        val gate = CompletableDeferred<RadioPage>()
        e.source.next = { gate.await() }
        e.active()
        val firstCall = launchCall { e.coordinator.loadNextPage() }
        runCurrent()
        assertNull(e.coordinator.loadNextPage())
        gate.complete(RadioPage(listOf(RadioItem("r3")), null))
        assertEquals(1, firstCall.await())
        assertEquals(1, e.source.nextCalls.size)
    }

    @Test fun endOfRadioStopsPagingForGood() = runTest {
        val e = env()
        e.active()
        assertEquals(1, e.coordinator.loadNextPage())
        assertTrue(e.coordinator.status.value.pagingEnded)
        assertNull(e.coordinator.loadNextPage(force = true))
        assertEquals(1, e.source.nextCalls.size)
    }

    @Test fun pagingErrorsNeverThrowAndOfferRetry() = runTest {
        val e = env()
        var calls = 0
        e.source.next = { if (++calls <= 3) throw RadioSourceException(RadioSourceException.Kind.RETRYABLE) else RadioPage(listOf(RadioItem("r3")), null) }
        e.active()
        val failing = launchCall { e.coordinator.loadNextPage() }
        advanceUntilIdle()
        assertNull(failing.await())
        assertTrue(e.coordinator.status.value.pagingNeedsRetry)
        assertTrue(e.player.appended.isEmpty())
        val retry = launchCall { e.coordinator.loadNextPage(force = true) }
        advanceUntilIdle()
        assertEquals(1, retry.await())
        assertFalse(e.coordinator.status.value.pagingNeedsRetry)
    }


    @Test fun aQueueEditDuringTheFetchDropsThePageAndTheSameContinuationIsFetchedAgain() = runTest {
        val e = env()
        val gate = CompletableDeferred<RadioPage>()
        var calls = 0
        e.source.next = { if (++calls == 1) gate.await() else RadioPage(listOf(RadioItem("r3")), null) }
        e.active()
        val first = launchCall { e.coordinator.loadNextPage() }
        runCurrent()
        e.player.generation++
        gate.complete(RadioPage(listOf(RadioItem("r3")), null))
        assertNull(first.await())
        assertTrue(e.player.appended.isEmpty())
        assertEquals(1, e.coordinator.loadNextPage())
        assertEquals(listOf("c1", "c1"), e.source.nextCalls)
        assertEquals(listOf(listOf("r3")), e.player.appended)
    }

    @Test fun deactivatingStopsPagingAndClearsTheStatus() = runTest {
        val e = env()
        e.active()
        e.coordinator.deactivate()
        assertNull(e.coordinator.loadNextPage(force = true))
        assertEquals(RadioState.IDLE, e.coordinator.status.value.state)
        assertTrue(e.player.appended.isEmpty())
    }

    @Test fun aLateOldPageNeverReachesTheReplacementRadio() = runTest {
        val e = env()
        val gate = CompletableDeferred<RadioPage>()
        var nextCalls = 0
        e.source.next = { if (++nextCalls == 1) gate.await() else RadioPage(listOf(RadioItem("new-r3")), null) }
        e.source.prepare = { RadioPage(listOf(RadioItem("r1")), "c1") }
        e.active()
        val oldPaging = launchCall { e.coordinator.loadNextPage() }
        runCurrent()
        e.source.prepare = { RadioPage(listOf(RadioItem("new-r1")), "n1") }
        assertTrue(e.coordinator.start(request()).await() is RadioOutcome.Active)
        gate.complete(RadioPage(listOf(RadioItem("old-r3")), null))
        assertNull(oldPaging.await())
        assertEquals(listOf(listOf("r1"), listOf("new-r1")), e.player.commits)
        assertTrue(e.player.appended.none { "old-r3" in it })
    }

    @Test fun aFailedReplacementKeepsTheOriginalSessionSource() = runTest {
        val e = env()
        var instances = 0
        val seenNext = mutableListOf<String>()
        e.sourceFactory = {
            val instance = ++instances
            object : RadioSourcePort {
                private var seed: String? = null
                override suspend fun prepare(seedCatalogId: String): RadioPage {
                    seed = seedCatalogId
                    return if (instance == 1) RadioPage(listOf(RadioItem("r1")), "old-c1") else RadioPage(emptyList(), null)
                }
                override suspend fun next(continuation: String): RadioPage {
                    seenNext += "$instance:$seed:$continuation"
                    return RadioPage(listOf(RadioItem("r2")), null)
                }
            }
        }
        e.active()
        e.links.link = e.links.link!!.copy(catalogId = "new-seed")
        assertEquals(RadioOutcome.Failed(FailureReason.EMPTY_FIRST_PAGE), e.coordinator.start(request()).await())
        assertEquals("s1", e.coordinator.status.value.activeSessionId)
        assertEquals(1, e.coordinator.loadNextPage())
        assertEquals(listOf("1:seed:old-c1"), seenNext)
    }

    @Test fun pagingRetryStillWorksAfterAFailedReplacementRequest() = runTest {
        val e = env(RadioConfig(maxRetries = 0))
        e.active()
        e.source.prepare = { RadioPage(emptyList(), null) }
        assertEquals(RadioOutcome.Failed(FailureReason.EMPTY_FIRST_PAGE), e.coordinator.start(request()).await())
        e.source.next = { throw RadioSourceException(RadioSourceException.Kind.OFFLINE) }
        assertNull(e.coordinator.loadNextPage())
        assertEquals("s1", e.coordinator.status.value.activeSessionId)
        assertTrue(e.coordinator.status.value.pagingNeedsRetry)
        e.source.next = { RadioPage(listOf(RadioItem("good")), null) }
        assertEquals(1, e.coordinator.loadNextPage(force = true))
        assertEquals("s1", e.coordinator.status.value.activeSessionId)
        assertFalse(e.coordinator.status.value.pagingNeedsRetry)
        assertTrue(e.coordinator.status.value.pagingEnded)
    }

    @Test fun anOldPageErrorDoesNotSetRetryOnTheNewRadio() = runTest {
        val e = env(RadioConfig(maxRetries = 0))
        val gate = CompletableDeferred<RadioPage>()
        e.source.next = { gate.await() }
        e.active()
        val oldPaging = launchCall { e.coordinator.loadNextPage() }
        runCurrent()
        assertTrue(e.coordinator.start(request()).await() is RadioOutcome.Active)
        gate.completeExceptionally(RadioSourceException(RadioSourceException.Kind.OFFLINE))
        assertNull(oldPaging.await())
        assertEquals("s2", e.coordinator.status.value.sessionId)
        assertFalse(e.coordinator.status.value.pagingNeedsRetry)
    }

    @Test fun repeatedAutomaticCallbacksStopAfterFilteredPagesUntilExplicitRetry() = runTest {
        val e = env(RadioConfig(maxEmptyPages = 2))
        e.source.next = { RadioPage(listOf(RadioItem("blocked", allowed = false)), "more") }
        e.active()
        assertNull(e.coordinator.loadNextPage())
        assertEquals(2, e.source.nextCalls.size)
        repeat(5) { assertNull(e.coordinator.loadNextPage()) }
        assertEquals(2, e.source.nextCalls.size)
        assertTrue(e.coordinator.status.value.pagingNeedsRetry)
        e.source.next = { RadioPage(listOf(RadioItem("good")), null) }
        assertEquals(1, e.coordinator.loadNextPage(force = true))
    }

    @Test fun unexpectedPagingFailureReleasesItsTicketForAnExplicitRetry() = runTest {
        val e = env()
        e.source.next = { throw IllegalStateException("filter failed") }
        e.active()
        assertNull(e.coordinator.loadNextPage())
        assertTrue(e.coordinator.status.value.pagingNeedsRetry)
        e.source.next = { RadioPage(listOf(RadioItem("good")), null) }
        assertEquals(1, e.coordinator.loadNextPage(force = true))
    }
}
