/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioCommitGuardTest {
    private val registry = RadioRequestRegistry()
    private val tracker = QueueGenerationTracker()

    private fun context(
        action: UserAction = UserAction.CURRENT,
        occurrence: String = "occ-1",
        playback: String = "local-1",
        revision: String = "rev-1",
    ) = RadioRequestContext(registry.newRequest(), tracker.generation, occurrence, playback, revision, action, 1_000L)

    private fun snapshot(
        ctx: RadioRequestContext,
        generation: Long = tracker.generation,
        occurrence: String? = "occ-1",
        playback: String? = "local-1",
        revision: String? = "rev-1",
        exists: Boolean = true,
        blend: Boolean = false,
    ) = QueueSnapshot(generation, occurrence, playback, revision, exists, registry.latestRequestId, blend)

    @Test fun untouchedQueueCommits() {
        val ctx = context()
        assertEquals(CommitCheck.Proceed, RadioCommitGuard.check(ctx, snapshot(ctx)))
    }

    @Test fun seekPauseHydrationAndAdvanceDoNotInvalidate() {
        val ctx = context()
        listOf(QueueEvent.SEEK, QueueEvent.PAUSE_RESUME, QueueEvent.HYDRATION, QueueEvent.PREFETCH, QueueEvent.OCCURRENCE_ADVANCED)
            .forEach { tracker.onEvent(it) }
        assertEquals(CommitCheck.Proceed, RadioCommitGuard.check(ctx, snapshot(ctx)))
    }

    @Test fun userQueueEditsReorderRemoveAndNewQueueInvalidate() {
        for (event in listOf(QueueEvent.USER_QUEUE_EDIT, QueueEvent.USER_REORDER, QueueEvent.USER_REMOVE, QueueEvent.NEW_QUEUE, QueueEvent.SOURCE_CHANGED)) {
            val ctx = context()
            tracker.onEvent(event)
            assertEquals(event.name, CommitCheck.Stale(StaleReason.QUEUE_CHANGED), RadioGuardAccess.check(ctx, snapshot(ctx)))
        }
    }

    @Test fun sameIndexButDifferentOccurrenceIsStale() {
        val ctx = context(occurrence = "occ-1")
        assertEquals(CommitCheck.Stale(StaleReason.ANCHOR_CHANGED), RadioCommitGuard.check(ctx, snapshot(ctx, occurrence = "occ-2")))
    }

    @Test fun movingToTheNextTrackWhileFetchingIsStaleForCurrentRadio() {
        val ctx = context()
        tracker.onEvent(QueueEvent.OCCURRENCE_ADVANCED)
        assertEquals(CommitCheck.Stale(StaleReason.ANCHOR_CHANGED), RadioCommitGuard.check(ctx, snapshot(ctx, occurrence = "occ-2")))
    }

    @Test fun newRadioSupersedesTheOldRequest() {
        val old = context()
        val newer = context()
        assertEquals(CommitCheck.Stale(StaleReason.SUPERSEDED), RadioCommitGuard.check(old, snapshot(old)))
        assertEquals(CommitCheck.Proceed, RadioCommitGuard.check(newer, snapshot(newer)))
        registry.invalidate()
        assertEquals(CommitCheck.Stale(StaleReason.SUPERSEDED), RadioCommitGuard.check(newer, snapshot(newer)))
    }

    @Test fun actualPlaybackIdChangeOrFileChangeIsStale() {
        val ctx = context()
        assertEquals(CommitCheck.Stale(StaleReason.SOURCE_CHANGED), RadioCommitGuard.check(ctx, snapshot(ctx, playback = "online-1")))
        assertEquals(CommitCheck.Stale(StaleReason.METADATA_CHANGED), RadioCommitGuard.check(ctx, snapshot(ctx, revision = "rev-2")))
        assertEquals(CommitCheck.Stale(StaleReason.METADATA_CHANGED), RadioCommitGuard.check(ctx, snapshot(ctx, revision = null)))
    }

    @Test fun blendInProgressDefersOnlyAValidCommit() {
        val ctx = context()
        assertEquals(CommitCheck.Defer, RadioCommitGuard.check(ctx, snapshot(ctx, blend = true)))
        assertEquals(CommitCheck.Stale(StaleReason.ANCHOR_CHANGED), RadioCommitGuard.check(ctx, snapshot(ctx, blend = true, occurrence = "occ-2")))
    }

    @Test fun selectedRadioIgnoresPlayerQueueButNeedsTheSelectedEntry() {
        val ctx = context(action = UserAction.SELECTED)
        tracker.onEvent(QueueEvent.USER_QUEUE_EDIT)
        assertEquals(CommitCheck.Proceed, RadioCommitGuard.check(ctx, snapshot(ctx, occurrence = "other", playback = "other")))
        assertEquals(CommitCheck.Stale(StaleReason.ANCHOR_REMOVED), RadioCommitGuard.check(ctx, snapshot(ctx, exists = false)))
        assertEquals(CommitCheck.Proceed, RadioCommitGuard.check(ctx, snapshot(ctx, blend = true)))
    }

    private object RadioGuardAccess {
        fun check(
            ctx: RadioRequestContext,
            s: QueueSnapshot,
        ) = RadioCommitGuard.check(ctx, s)
    }
}

class RadioStateMachineTest {
    private fun machine(vararg events: RadioEvent) = RadioStateMachine().also { m -> events.forEach { m.accept(it) } }

    @Test fun happyPathReachesActive() {
        val m = machine(RadioEvent.Start, RadioEvent.Resolved, RadioEvent.FirstPageReady(true), RadioEvent.Committed)
        assertEquals(RadioState.ACTIVE, m.state)
    }

    @Test fun choicePathGoesThroughNeedsChoice() {
        val m = machine(RadioEvent.Start, RadioEvent.ChoiceRequired)
        assertEquals(RadioState.NEEDS_CHOICE, m.state)
        m.accept(RadioEvent.ChoiceMade)
        assertEquals(RadioState.FETCHING, m.state)
    }

    @Test fun emptyFirstPageNeverCommits() {
        val m = machine(RadioEvent.Start, RadioEvent.Resolved)
        val t = m.accept(RadioEvent.FirstPageReady(false))
        assertEquals(RadioState.FAILED, m.state)
        assertEquals(FailureReason.EMPTY_FIRST_PAGE, t.failure)
        assertFalse(m.accept(RadioEvent.Committed).accepted)
    }

    @Test fun everyInFlightStateCanEndWithoutCommit() {
        val prefixes = listOf(
            arrayOf<RadioEvent>(RadioEvent.Start),
            arrayOf(RadioEvent.Start, RadioEvent.ChoiceRequired),
            arrayOf(RadioEvent.Start, RadioEvent.Resolved),
            arrayOf(RadioEvent.Start, RadioEvent.Resolved, RadioEvent.FirstPageReady(true)),
        )
        for (p in prefixes) {
            assertEquals(RadioState.CANCELLED, machine(*p, RadioEvent.Cancel).state)
            assertEquals(RadioState.FAILED, machine(*p, RadioEvent.Fail(FailureReason.OFFLINE)).state)
            assertEquals(RadioState.STALE, machine(*p, RadioEvent.MarkStale(StaleReason.QUEUE_CHANGED)).state)
        }
    }

    @Test fun illegalEventsAreRejectedWithoutChangingState() {
        val m = machine(RadioEvent.Start)
        val t = m.accept(RadioEvent.Committed)
        assertFalse(t.accepted)
        assertEquals(RadioState.RESOLVING, m.state)
        assertFalse(RadioStateMachine().accept(RadioEvent.Resolved).accepted)
        assertFalse(RadioStateMachine().accept(RadioEvent.Cancel).accepted)
    }

    @Test fun terminalStatesAcceptOnlyANewStartAndActiveCanExitOrRestart() {
        val failed = machine(RadioEvent.Start, RadioEvent.Fail(FailureReason.RETRYABLE))
        assertFalse(failed.accept(RadioEvent.Resolved).accepted)
        assertTrue(failed.accept(RadioEvent.Start).accepted)
        val active = machine(RadioEvent.Start, RadioEvent.Resolved, RadioEvent.FirstPageReady(true), RadioEvent.Committed)
        assertTrue(active.accept(RadioEvent.Start).accepted)
        assertEquals(RadioState.RESOLVING, active.state)
        val exit = machine(RadioEvent.Start, RadioEvent.Resolved, RadioEvent.FirstPageReady(true), RadioEvent.Committed)
        assertTrue(exit.accept(RadioEvent.Exit).accepted)
        assertEquals(RadioState.IDLE, exit.state)
    }

    @Test fun startWhileResolvingIsRejectedBecauseTheCallerMustCancelFirst() {
        val m = machine(RadioEvent.Start)
        assertFalse(m.accept(RadioEvent.Start).accepted)
    }
}

class RadioModeControllerTest {
    private val modes = PlayerModes(shuffle = true, repeat = RepeatMode.ALL)
    private val a = OccurrenceRef("o-1", "pl", 3, "e-1")
    private val tail = listOf(OccurrenceRef("o-2", "pl", 3, "e-2"), OccurrenceRef("o-3", "pl", 3, "e-3"))
    private val context = ReturnContext("pl", 3, "o-1", tail, modes)

    @Test fun enteringRadioTurnsShuffleOffAndKeepsRepeat() {
        val c = RadioModeController()
        val plan = c.enter(modes, context)
        assertEquals(PlayerModes(false, RepeatMode.ALL), plan.modesToApply)
        assertTrue(plan.canOfferReturn)
        assertFalse(plan.keptOriginalReturn)
    }

    @Test fun libraryScreenWithoutPlaylistOffersNoGuessedReturn() {
        val plan = RadioModeController().enter(modes, null)
        assertFalse(plan.canOfferReturn)
    }

    @Test fun secondRadioKeepsTheOriginalReturnContext() {
        val c = RadioModeController()
        c.enter(modes, context)
        val second = c.enter(PlayerModes(false, RepeatMode.ALL), ReturnContext("other", 9, "x", emptyList(), PlayerModes(false, RepeatMode.OFF)))
        assertTrue(second.keptOriginalReturn)
        assertEquals(context, c.returnContext)
        val back = c.exit(OccurrenceRef("playing"), 3)!!
        assertEquals(modes, back.modesToRestore)
    }

    @Test fun returnKeepsThePlayingItemAndRestoresTheTail() {
        val c = RadioModeController()
        c.enter(modes, context)
        val playing = OccurrenceRef("online-9")
        val plan = c.exit(playing, 3)!!
        assertEquals(listOf(playing) + tail, plan.queue)
        assertFalse(plan.playlistRefreshedSince)
        assertFalse(c.active)
        assertNull(c.returnContext)
    }

    @Test fun refreshedPlaylistIsReportedButSnapshotIsStillRestored() {
        val c = RadioModeController()
        c.enter(modes, context)
        val plan = c.exit(a, 4)!!
        assertTrue(plan.playlistRefreshedSince)
        assertEquals(listOf(a) + tail, plan.queue)
    }

    @Test fun exitWithoutRadioOrWithoutContextReturnsNull() {
        assertNull(RadioModeController().exit(a, 3))
        val c = RadioModeController()
        c.enter(modes, null)
        assertNull(c.exit(a, 3))
        assertFalse(c.active)
    }

    @Test fun autoPaginationOnlyWithRepeatOff() {
        assertTrue(RadioPaginationPolicy.mayAutoFetch(RepeatMode.OFF))
        assertFalse(RadioPaginationPolicy.mayAutoFetch(RepeatMode.ONE))
        assertFalse(RadioPaginationPolicy.mayAutoFetch(RepeatMode.ALL))
        assertTrue(RadioPaginationPolicy.repeatLimitsAppending(RepeatMode.ALL))
        assertFalse(RadioPaginationPolicy.repeatLimitsAppending(RepeatMode.OFF))
    }

    @Test fun fetchDecisionHonoursInFlightEndAndLowWater() {
        fun f(remaining: Int, repeat: RepeatMode = RepeatMode.OFF, active: Boolean = true, inFlight: Boolean = false, ended: Boolean = false) =
            RadioPaginationPolicy.shouldFetch(remaining, 3, repeat, active, inFlight, ended)
        assertTrue(f(2))
        assertFalse(f(10))
        assertFalse(f(2, repeat = RepeatMode.ONE))
        assertFalse(f(2, active = false))
        assertFalse(f(2, inFlight = true))
        assertFalse(f(2, ended = true))
    }

    @Test fun switchingRepeatToOffTriggersAControlledRefillOnlyWhenLow() {
        assertTrue(RadioPaginationPolicy.refillOnRepeatChange(RepeatMode.ALL, RepeatMode.OFF, 1, 3))
        assertFalse(RadioPaginationPolicy.refillOnRepeatChange(RepeatMode.ALL, RepeatMode.OFF, 20, 3))
        assertFalse(RadioPaginationPolicy.refillOnRepeatChange(RepeatMode.OFF, RepeatMode.ONE, 1, 3))
    }
}

class RadioPaginatorTest {
    private fun item(id: String, allowed: Boolean = true, vararg alias: String) = RadioItem(id, alias.toSet(), allowed)

    private fun paginator(seed: Set<String> = setOf("seed"), maxEmpty: Int = 3) = RadioPaginator("s1", 5, seed, maxEmpty)

    private fun RadioPaginator.fetch(page: RadioPage, session: String = "s1", generation: Long = 5): PageOutcome {
        val t = checkNotNull(startFetch(session, generation))
        return onPage(t, page, session, generation)
    }

    @Test fun seedAndItsAliasesAreRemovedOnEveryPage() {
        val p = paginator(setOf("seed", "seed-alias"))
        val out = p.fetch(RadioPage(listOf(item("a"), item("seed"), item("seed-alias"), item("b")), "c1")) as PageOutcome.Append
        assertEquals(listOf("a", "b"), out.items.map { it.catalogId })
        val out2 = p.fetch(RadioPage(listOf(item("seed"), item("c")), "c2")) as PageOutcome.Append
        assertEquals(listOf("c"), out2.items.map { it.catalogId })
    }

    @Test fun onlyOneFetchAtATime() {
        val p = paginator()
        val t = p.startFetch("s1", 5)
        assertNotNull(t)
        assertNull(p.startFetch("s1", 5))
        assertTrue(p.fetchInFlight)
        p.abort(t!!)
        assertNotNull(p.startFetch("s1", 5))
    }

    @Test fun staleSessionOrGenerationNeverAppends() {
        val p = paginator()
        val t = checkNotNull(p.startFetch("s1", 5))
        assertEquals(PageOutcome.Stale, p.onPage(t, RadioPage(listOf(item("a")), "c"), "other-session", 5))
        val p2 = paginator()
        val t2 = checkNotNull(p2.startFetch("s1", 5))
        assertEquals(PageOutcome.Stale, p2.onPage(t2, RadioPage(listOf(item("a")), "c"), "s1", 6))
        assertNull(paginator().startFetch("s1", 6))
        assertNull(paginator().startFetch("other", 5))
    }

    @Test fun adoptingANewGenerationInvalidatesTheInFlightResult() {
        val p = paginator()
        val t = checkNotNull(p.startFetch("s1", 5))
        p.adoptGeneration(6)
        assertEquals(PageOutcome.Stale, p.onPage(t, RadioPage(listOf(item("a")), "c"), "s1", 6))
        val t2 = checkNotNull(p.startFetch("s1", 6))
        assertTrue(p.onPage(t2, RadioPage(listOf(item("a")), "c"), "s1", 6) is PageOutcome.Append)
    }

    @Test fun repeatedBoundaryItemIsDroppedButANewFirstItemIsNeverLost() {
        val withRepeat = paginator()
        withRepeat.fetch(RadioPage(listOf(item("a"), item("b")), "c1"))
        val dropped = withRepeat.fetch(RadioPage(listOf(item("b"), item("c")), "c2")) as PageOutcome.Append
        assertEquals(listOf("c"), dropped.items.map { it.catalogId })
        val withoutRepeat = paginator()
        withoutRepeat.fetch(RadioPage(listOf(item("a"), item("b")), "c1"))
        val kept = withoutRepeat.fetch(RadioPage(listOf(item("x"), item("y")), "c2")) as PageOutcome.Append
        assertEquals(listOf("x", "y"), kept.items.map { it.catalogId })
    }

    @Test fun deduplicatesByCatalogIdAndConfirmedAliasesNotByTitle() {
        val p = paginator()
        p.fetch(RadioPage(listOf(item("a", true, "a-album")), "c1"))
        val out = p.fetch(RadioPage(listOf(item("a-album"), item("a"), item("a-other-release")), "c2")) as PageOutcome.Append
        assertEquals(listOf("a-other-release"), out.items.map { it.catalogId })
    }

    @Test fun blockedItemsAreFilteredWithoutEndingTheSession() {
        val p = paginator()
        val out = p.fetch(RadioPage(listOf(item("a", allowed = false), item("b")), "c1")) as PageOutcome.Append
        assertEquals(listOf("b"), out.items.map { it.catalogId })
        assertFalse(p.ended)
    }

    @Test fun emptyAfterFilterWithContinuationRetriesThenStopsInsteadOfLooping() {
        val p = paginator(maxEmpty = 3)
        val blocked = RadioPage(listOf(item("a", allowed = false)), "more")
        val first = p.fetch(blocked) as PageOutcome.Append
        assertTrue(first.items.isEmpty() && first.fetchAgain)
        assertTrue((p.fetch(blocked) as PageOutcome.Append).fetchAgain)
        assertEquals(PageOutcome.RetryOrEnd, p.fetch(blocked))
        p.resetEmptyStreak()
        assertTrue(p.fetch(blocked) is PageOutcome.Append)
    }

    @Test fun aGoodPageResetsTheEmptyStreak() {
        val p = paginator(maxEmpty = 2)
        p.fetch(RadioPage(listOf(item("a", allowed = false)), "m"))
        p.fetch(RadioPage(listOf(item("b")), "m"))
        val again = p.fetch(RadioPage(listOf(item("c", allowed = false)), "m")) as PageOutcome.Append
        assertTrue(again.fetchAgain)
    }

    @Test fun missingContinuationEndsTheSession() {
        val p = paginator()
        assertTrue(p.fetch(RadioPage(listOf(item("a")), null)) is PageOutcome.Append)
        assertTrue(p.ended)
        assertNull(p.startFetch("s1", 5))
        val q = paginator()
        assertEquals(PageOutcome.End, q.fetch(RadioPage(emptyList(), null)))
    }

    @Test fun firstFetchHasNoContinuationAndLaterOnesUseTheReturnedOne() {
        val p = paginator()
        val t1 = checkNotNull(p.startFetch("s1", 5))
        assertNull(t1.continuation)
        p.onPage(t1, RadioPage(listOf(item("a")), "tok-1"), "s1", 5)
        assertEquals("tok-1", checkNotNull(p.startFetch("s1", 5)).continuation)
    }

    @Test fun persistedSeenSnapshotIsBoundedAndRestorable() {
        val p = RadioPaginator("s1", 5, emptySet())
        p.fetch(RadioPage((1..8).map { item("i$it") }, "c"))
        assertEquals(listOf("i4", "i5", "i6", "i7", "i8"), p.seenSnapshot(limit = 5))
        val restored = RadioPaginator("s1", 5, emptySet())
        restored.restoreSeen(p.seenSnapshot(limit = 5))
        val out = restored.fetch(RadioPage(listOf(item("i8"), item("i9")), "c")) as PageOutcome.Append
        assertEquals(listOf("i9"), out.items.map { it.catalogId })
    }

    @Test fun rollbackForgetsTheLastPageSoItCanBeOfferedAgain() {
        val p = paginator()
        p.fetch(RadioPage(listOf(item("a"), item("b")), "c1"))
        val before = checkNotNull(p.startFetch("s1", 5))
        assertEquals("c1", before.continuation)
        val out = p.onPage(before, RadioPage(listOf(item("c"), item("d")), "c2"), "s1", 5) as PageOutcome.Append
        assertEquals(listOf("c", "d"), out.items.map { it.catalogId })
        p.rollbackLastPage()
        val again = checkNotNull(p.startFetch("s1", 5))
        assertEquals("c1", again.continuation)
        val second = p.onPage(again, RadioPage(listOf(item("c"), item("d")), "c2"), "s1", 5) as PageOutcome.Append
        assertEquals(listOf("c", "d"), second.items.map { it.catalogId })
    }

    @Test fun rollbackOfTheVeryFirstPageRestoresTheFirstFetch() {
        val p = paginator()
        p.fetch(RadioPage(listOf(item("a")), "c1"))
        p.rollbackLastPage()
        assertNull(checkNotNull(p.startFetch("s1", 5)).continuation)
    }

    @Test fun rollbackAlsoRestoresTheEndFlag() {
        val p = paginator()
        p.fetch(RadioPage(listOf(item("a")), null))
        assertTrue(p.ended)
        p.rollbackLastPage()
        assertFalse(p.ended)
    }

    @Test fun persistedSeenLimitNeverEvictsTheSeedOrItsAliases() {
        val p = RadioPaginator("s1", 5, setOf("seed", "seed-alias"))
        p.fetch(RadioPage(listOf(item("a"), item("b"), item("c")), "c1"))
        p.seenSnapshot(limit = 2)
        val out = p.fetch(RadioPage(listOf(item("seed"), item("seed-alias"), item("d", true, "seed"), item("e")), "c2")) as PageOutcome.Append
        assertEquals(listOf("e"), out.items.map { it.catalogId })
    }

    @Test fun confirmedSeedAliasesDiscoveredOnAPageStayFilteredOnLaterPages() {
        val p = paginator(setOf("seed"))
        p.fetch(RadioPage(listOf(item("seed", true, "seed-alias"), item("a")), "c1"))
        val out = p.fetch(RadioPage(listOf(item("seed-alias"), item("b")), "c2")) as PageOutcome.Append
        assertEquals(listOf("b"), out.items.map { it.catalogId })
    }

    @Test fun aliasesDiscoveredOnADuplicateStayDeduplicatedOnLaterPages() {
        val p = paginator()
        p.fetch(RadioPage(listOf(item("a")), "c1"))
        p.fetch(RadioPage(listOf(item("a", true, "a-alias"), item("b")), "c2"))
        val out = p.fetch(RadioPage(listOf(item("a-alias"), item("c")), "c3")) as PageOutcome.Append
        assertEquals(listOf("c"), out.items.map { it.catalogId })
    }

    @Test fun rollbackRestoresHistoryFromBeforeTheRejectedPage() {
        val p = RadioPaginator("s1", 5, emptySet())
        p.fetch(RadioPage(listOf(item("a"), item("b")), "c1"))
        p.fetch(RadioPage(listOf(item("c"), item("d")), "c2"))
        p.rollbackLastPage()
        assertEquals(listOf("a", "b"), p.seenSnapshot())
        val out = p.fetch(RadioPage(listOf(item("a"), item("b"), item("e")), "c2")) as PageOutcome.Append
        assertEquals(listOf("e"), out.items.map { it.catalogId })
    }

    @Test fun runtimeDedupKeepsTheEntireSessionBeyondTwoThousandIds() {
        val p = paginator()
        p.fetch(RadioPage((1..2100).map { item("i$it") }, "c1"))
        assertEquals(500, p.seenSnapshot().size)
        val out = p.fetch(RadioPage(listOf(item("i1"), item("i50"), item("new")), "c2")) as PageOutcome.Append
        assertEquals(listOf("new"), out.items.map { it.catalogId })
    }

    @Test fun localRepeatsAreNotTouchedBecauseOnlyOnlineItemsGoThroughThePaginator() {
        // The paginator sees online catalog ids only; a local playlist may repeat a song and is never passed here.
        val p = paginator()
        val out = p.fetch(RadioPage(listOf(item("x"), item("y")), "c")) as PageOutcome.Append
        assertEquals(2, out.items.size)
    }
}
