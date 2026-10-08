/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

/** One radio result. [aliasIds] are confirmed equivalent catalog ids (other releases of the same recording). */
data class RadioItem(
    val catalogId: String,
    val aliasIds: Set<String> = emptySet(),
    /** Blocked by the user's rules, explicit filter or non-song content; decided by the caller. */
    val allowed: Boolean = true,
    /** Opaque carrier for the adapter (the original service item, so the player adapter can build a MediaItem). Never inspected here. */
    val payload: Any? = null,
)

data class RadioPage(
    val items: List<RadioItem>,
    val continuation: String?,
)

data class FetchTicket(
    val sessionId: String,
    val generation: Long,
    val continuation: String?,
    val serial: Long,
)

sealed interface PageOutcome {
    /** Items to append, already filtered and deduplicated. May fetch another page right away when [fetchAgain] is set. */
    data class Append(
        val items: List<RadioItem>,
        val fetchAgain: Boolean = false,
    ) : PageOutcome

    /** Session or generation changed while the request was in flight: nothing may be appended. */
    data object Stale : PageOutcome

    /** Filtered pages stayed empty although the service has more: stop looping, offer retry. */
    data object RetryOrEnd : PageOutcome

    /** No continuation left. */
    data object End : PageOutcome
}

/**
 * Paging for one radio session. At most one fetch is in flight, a result is accepted only for the session and generation
 * it was requested for, the seed (and its aliases) is removed on every page, items are deduplicated by catalog id or
 * confirmed alias across the whole session, and the repeated boundary item is dropped only when the page really repeats it.
 */
class RadioPaginator(
    val sessionId: String,
    initialGeneration: Long,
    seedIds: Set<String>,
    private val maxEmptyPages: Int = 3,
) {
    private val seeds = seedIds.toMutableSet()
    private val seen = LinkedHashSet<String>()
    private var generation = initialGeneration
    private var continuation: String? = null
    private var started = false
    private var inFlight: FetchTicket? = null
    private var serial = 0L
    private var emptyStreak = 0
    private var lastAppended: String? = null
    private var lastApplied: AppliedPage? = null

    private class AppliedPage(
        val addedIds: List<String>,
        val previousContinuation: String?,
        val previousLastAppended: String?,
        val previousEnded: Boolean,
        val previousEmptyStreak: Int,
        val previousStarted: Boolean,
    )

    var ended = false
        private set

    val fetchInFlight: Boolean get() = inFlight != null

    /** Newest ids for persistence ("bounded persisted seenCatalogIds"). */
    fun seenSnapshot(limit: Int = 500): List<String> = seen.toList().takeLast(limit)

    fun restoreSeen(ids: Collection<String>) {
        seen.addAll(ids)
    }

    fun persistedState(): PersistedRadio = PersistedRadio(sessionId, seeds.toList(), seenSnapshot(), continuation, ended)

    /** Restart starts from the seed on the next requested fetch. Continuations belong to the old source instance. */
    fun restoreState(saved: PersistedRadio) {
        require(saved.sessionId == sessionId)
        seeds.addAll(saved.seedCatalogIds)
        restoreSeen(saved.seenCatalogIds)
        ended = saved.ended
        started = false
        continuation = null
    }

    /** The queue was changed by the user (generation moved) but the session continues: adopt the new generation. */
    fun adoptGeneration(newGeneration: Long) {
        generation = newGeneration
        inFlight = null
    }

    /** Returns null when nothing may be fetched now (already in flight, ended, or wrong session). */
    fun startFetch(
        activeSessionId: String,
        currentGeneration: Long,
    ): FetchTicket? {
        if (activeSessionId != sessionId || currentGeneration != generation || ended || inFlight != null) return null
        val t = FetchTicket(sessionId, generation, if (started) continuation else null, ++serial)
        inFlight = t
        return t
    }

    /** The in-flight job was cancelled or failed with a retryable error; allows the next fetch. */
    fun abort(ticket: FetchTicket) {
        if (inFlight == ticket) inFlight = null
    }

    fun onPage(
        ticket: FetchTicket,
        page: RadioPage,
        activeSessionId: String,
        currentGeneration: Long,
    ): PageOutcome {
        if (inFlight != ticket) return PageOutcome.Stale
        inFlight = null
        if (ticket.sessionId != sessionId || activeSessionId != sessionId || ticket.generation != currentGeneration || currentGeneration != generation) {
            return PageOutcome.Stale
        }
        val addedIds = mutableListOf<String>()
        val previous = AppliedPage(addedIds, continuation, lastAppended, ended, emptyStreak, started)
        started = true
        continuation = page.continuation
        val fresh = mutableListOf<RadioItem>()
        // The seen set removes a repeated boundary while retaining any newly confirmed aliases carried by it.
        for (item in page.items) {
            if (!item.allowed) continue
            val ids = listOf(item.catalogId) + item.aliasIds
            if (ids.any { it in seeds }) {
                seeds.addAll(ids)
                continue
            }
            if (ids.any { it in seen }) {
                addedIds += ids.filter { it !in seen }
                seen.addAll(ids)
                continue
            }
            addedIds += ids.filter { it !in seen }
            seen.addAll(ids)
            fresh += item
        }
        lastApplied = previous
        val hasMore = page.continuation != null
        if (fresh.isNotEmpty()) {
            emptyStreak = 0
            lastAppended = fresh.last().catalogId
            if (!hasMore) ended = true
            return PageOutcome.Append(fresh)
        }
        if (!hasMore) {
            ended = true
            return PageOutcome.End
        }
        emptyStreak++
        return if (emptyStreak >= maxEmptyPages) PageOutcome.RetryOrEnd else PageOutcome.Append(emptyList(), fetchAgain = true)
    }

    /**
     * The page returned by the last [onPage] could not be applied to the player (the queue changed or the session was replaced).
     * Forget it: its ids may be offered again and the same continuation is fetched again, so nothing is silently lost.
     */
    fun rollbackLastPage() {
        val applied = lastApplied ?: return
        lastApplied = null
        seen.removeAll(applied.addedIds.toSet())
        continuation = applied.previousContinuation
        lastAppended = applied.previousLastAppended
        ended = applied.previousEnded
        emptyStreak = applied.previousEmptyStreak
        started = applied.previousStarted
    }

    /** User asked to retry after [PageOutcome.RetryOrEnd]. */
    fun resetEmptyStreak() {
        emptyStreak = 0
    }

}
