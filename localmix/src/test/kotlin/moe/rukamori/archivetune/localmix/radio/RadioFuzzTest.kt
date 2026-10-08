/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Seeded random sequences against the pure radio components: invariants must hold for every order of events. */
class RadioFuzzTest {
    @Test fun stateMachineNeverEntersAnImpossibleStateAndNeverCommitsWithoutAFirstPage() {
        val rnd = Random(20261007)
        val events = listOf(
            RadioEvent.Start, RadioEvent.Resolved, RadioEvent.ChoiceRequired, RadioEvent.ChoiceMade, RadioEvent.FirstPageReady(true),
            RadioEvent.FirstPageReady(false), RadioEvent.Committed, RadioEvent.Cancel, RadioEvent.Fail(FailureReason.OFFLINE),
            RadioEvent.MarkStale(StaleReason.QUEUE_CHANGED), RadioEvent.Exit,
        )
        repeat(2_000) {
            val m = RadioStateMachine()
            var sawFirstPage = false
            repeat(40) {
                val e = events[rnd.nextInt(events.size)]
                val before = m.state
                val t = m.accept(e)
                assertEquals(before, t.from)
                if (!t.accepted) assertEquals(before, m.state)
                if (e == RadioEvent.Start && t.accepted) sawFirstPage = false
                if (e == RadioEvent.FirstPageReady(true) && t.accepted) sawFirstPage = true
                // ACTIVE is only reachable through READY_TO_COMMIT, which needs a non-empty first page of the same attempt.
                if (t.accepted && m.state == RadioState.ACTIVE && before != RadioState.ACTIVE) assertTrue(sawFirstPage)
                if (before == RadioState.IDLE) assertTrue(m.state == RadioState.IDLE || m.state == RadioState.RESOLVING)
            }
        }
    }

    @Test fun paginatorNeverAppendsADuplicateOrTheSeedAndNeverHasTwoFetchesInFlight() {
        val rnd = Random(7)
        repeat(500) {
            val seed = setOf("seed", "seed-alias")
            val p = RadioPaginator("s", 1, seed, maxEmptyPages = 3)
            val appended = mutableListOf<String>()
            var generation = 1L
            var ticket: FetchTicket? = null
            repeat(60) {
                when (rnd.nextInt(5)) {
                    0 -> if (ticket == null) {
                        ticket = p.startFetch("s", generation)
                    } else {
                        assertFalse("second fetch while one is in flight", p.startFetch("s", generation) != null)
                    }
                    1 -> ticket?.let { t ->
                        val items = List(rnd.nextInt(5)) { RadioItem("i${rnd.nextInt(40)}", if (rnd.nextInt(6) == 0) setOf("seed") else emptySet(), rnd.nextInt(4) != 0) }
                        val page = RadioPage(items, if (rnd.nextInt(5) == 0) null else "c")
                        val out = p.onPage(t, page, "s", generation)
                        ticket = null
                        if (out is PageOutcome.Append) out.items.forEach { appended += it.catalogId }
                    }
                    2 -> ticket?.let { p.abort(it); ticket = null }
                    3 -> { generation++; p.adoptGeneration(generation); ticket = null }
                    else -> p.resetEmptyStreak()
                }
            }
            assertEquals("duplicates appended: $appended", appended.size, appended.toSet().size)
            assertFalse(appended.any { it == "seed" || it == "seed-alias" })
        }
    }

    @Test fun commitGuardNeverProceedsForAnythingButTheNewestUnchangedRequest() {
        val rnd = Random(99)
        repeat(3_000) {
            val registry = RadioRequestRegistry()
            val tracker = QueueGenerationTracker()
            val action = if (rnd.nextBoolean()) UserAction.CURRENT else UserAction.SELECTED
            val id = registry.newRequest()
            val ctx = RadioRequestContext(id, tracker.generation, "occ", "play", "rev", action, 0)
            var changed = false
            repeat(rnd.nextInt(6)) {
                when (rnd.nextInt(4)) {
                    0 -> { tracker.onEvent(QueueEvent.entries[rnd.nextInt(QueueEvent.entries.size)]); }
                    1 -> registry.newRequest()
                    2 -> registry.invalidate()
                    else -> {}
                }
            }
            val occurrence = if (rnd.nextInt(5) == 0) "other" else "occ"
            val snapshot = QueueSnapshot(tracker.generation, occurrence, "play", "rev", true, registry.latestRequestId, blendInProgress = false)
            val result = RadioCommitGuard.check(ctx, snapshot)
            val newest = registry.latestRequestId == id
            val sameQueue = action == UserAction.SELECTED || (tracker.generation == ctx.queueGeneration && occurrence == "occ")
            changed = !newest || !sameQueue
            if (result == CommitCheck.Proceed) assertFalse("proceeded although the request was outdated", changed)
            if (!changed) assertEquals(CommitCheck.Proceed, result)
        }
    }
}
