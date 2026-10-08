/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.LocalAsset
import moe.rukamori.archivetune.localmix.RecordingResolver
import moe.rukamori.archivetune.localmix.ResolveResult
import moe.rukamori.archivetune.localmix.ScoredCandidate
import kotlin.coroutines.coroutineContext

/** What the player knows right now. The adapter reads it on the player thread; the coordinator never touches the player directly. */
data class PlayerQueueView(
    val generation: Long,
    val currentOccurrenceId: String?,
    val currentActualPlaybackId: String?,
    val anchorMetadataRevision: String?,
    val anchorStillExists: Boolean,
    val blendInProgress: Boolean,
    val modes: PlayerModes,
    val remainingAhead: Int,
)

interface RadioPlayerPort {
    suspend fun view(anchorOccurrenceId: String): PlayerQueueView

    /**
     * Replaces only the future tail of the running queue; the playing item, position, pause state and volume stay.
     * Re-check context and stillValid on the player thread immediately before writing. Return the committed generation,
     * or null if the request moved on. No suspension is allowed between the final check and the queue mutation.
     */
    suspend fun commitCurrent(
        sessionId: String,
        items: List<RadioItem>,
        modes: PlayerModes,
        context: RadioRequestContext,
        stillValid: () -> Boolean,
    ): Long?

    /** Starts the selected local item first, then the radio items. Called only with a complete prepared result. */
    suspend fun commitSelected(
        sessionId: String,
        selected: OccurrenceRef,
        items: List<RadioItem>,
        modes: PlayerModes,
        context: RadioRequestContext,
        stillValid: () -> Boolean,
    ): Long?

    suspend fun appendTail(
        sessionId: String,
        items: List<RadioItem>,
        expectedGeneration: Long,
    ): Boolean
}

class RadioSourceException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { OFFLINE, RETRYABLE }
}

interface RadioSourcePort {
    /** First radio page for the seed catalog recording (the existing YouTubeQueue seed/fetch). Throws [RadioSourceException]. */
    suspend fun prepare(seedCatalogId: String): RadioPage

    suspend fun next(continuation: String): RadioPage
}

data class RadioStartRequest(
    val asset: LocalAsset,
    val anchor: OccurrenceRef,
    val actualPlaybackId: String,
    val action: UserAction,
)

data class RadioStatus(
    val state: RadioState = RadioState.IDLE,
    val requestId: Long? = null,
    val sessionId: String? = null,
    val candidates: List<ScoredCandidate> = emptyList(),
    val failure: FailureReason? = null,
    val stale: StaleReason? = null,
    /** True while paging gave up for now (empty filtered pages or an error); the UI offers retry. */
    val pagingNeedsRetry: Boolean = false,
    val pagingEnded: Boolean = false,
    /** Playing session survives a failed or cancelled attempt to replace it. */
    val activeSessionId: String? = null,
)

sealed interface RadioOutcome {
    data class Active(
        val sessionId: String,
        val firstPageSize: Int,
    ) : RadioOutcome

    data class Failed(
        val reason: FailureReason,
    ) : RadioOutcome

    data class Stale(
        val reason: StaleReason,
    ) : RadioOutcome
}

data class RadioConfig(
    val prepareTimeoutMs: Long = 15_000,
    val pageTimeoutMs: Long = 10_000,
    val maxRetries: Int = 2,
    val backoffBaseMs: Long = 500,
    val deferPollMs: Long = 100,
    val maxDeferPolls: Int = 50,
    val lowWater: Int = 3,
    val maxEmptyPages: Int = 3,
)

/**
 * Orchestrates local-to-radio (RADIO_FLOW.md) without Android types: resolve the seed, fetch the first page, commit to the
 * player only if the request is still valid, then page. The player is never changed on failure, cancellation or staleness.
 * Only the newest request survives; an older one is cancelled and cannot write anything. Each request owns its source and
 * there is one page fetch per session at a time, so replacement preparation does not mutate or block the existing radio.
 * A cancelled run (user closed the dialog, newer request) throws CancellationException from the returned [Deferred].
 * Not thread-safe by design: call start/choose/cancel/deactivate/loadNextPage from one dispatcher (the service main thread);
 * the port implementations are responsible for hopping to the player looper.
 */
class LocalRadioCoordinator(
    private val scope: CoroutineScope,
    private val resolver: RecordingResolver,
    private val sourceFactory: () -> RadioSourcePort,
    private val player: RadioPlayerPort,
    private val config: RadioConfig = RadioConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionIds: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    private val registry = RadioRequestRegistry()
    private val mutableStatus = MutableStateFlow(RadioStatus())
    val status: StateFlow<RadioStatus> = mutableStatus.asStateFlow()

    private var currentRun: Deferred<RadioOutcome>? = null
    private var pendingChoice: CompletableDeferred<CatalogCandidate?>? = null
    private var session: ActiveSession? = null

    private class ActiveSession(
        val id: String,
        val paginator: RadioPaginator,
        var generation: Long,
        val source: RadioSourcePort,
        val seedCatalogId: String,
    ) {
        val pageLock = Mutex()
        var needsRetry = false
    }

    /** Failure carrier: a step that cannot continue throws it; [run] turns it into a FAILED outcome without touching the player. */
    private class StepFailure(
        val reason: FailureReason,
    ) : Exception()

    private class StepStale(val reason: StaleReason) : Exception()
    private data class ResolvedSeed(val catalogId: String, val ids: Set<String>)

    /** Starts radio for [request]; a previous run is cancelled first. */
    fun start(request: RadioStartRequest): Deferred<RadioOutcome> = startCommon(request, forceChoice = false)

    /** Explicit user retry opens manual search even for a remembered "no correct match". Never auto-resolves. */
    fun startManual(request: RadioStartRequest): Deferred<RadioOutcome> = startCommon(request, forceChoice = true)

    private fun startCommon(request: RadioStartRequest, forceChoice: Boolean): Deferred<RadioOutcome> {
        currentRun?.cancel(CancellationException("superseded"))
        pendingChoice?.cancel()
        val requestId = registry.newRequest()
        val deferred = scope.async { run(request, requestId, forceChoice) }
        currentRun = deferred
        return deferred
    }

    /** The user picked a candidate (null = "no correct match"). Ignored unless a choice is pending. */
    fun choose(candidate: CatalogCandidate?) {
        pendingChoice?.complete(candidate)
    }

    /** Dialog closed or the user left: cancels the pending request; music keeps playing. */
    fun cancel() {
        registry.invalidate()
        pendingChoice?.cancel()
        currentRun?.cancel(CancellationException("cancelled by user"))
        val s = mutableStatus.value
        if (s.state == RadioState.RESOLVING || s.state == RadioState.NEEDS_CHOICE || s.state == RadioState.FETCHING || s.state == RadioState.READY_TO_COMMIT) {
            mutableStatus.value = s.copy(state = RadioState.CANCELLED, candidates = emptyList())
        }
    }

    /** Adapter validation failed before a LocalAsset could be built. Keep a previously committed radio alive. */
    fun rejectPreparation(reason: FailureReason) {
        finishPreparation(RadioState.FAILED, failure = reason)
    }

    fun stalePreparation(reason: StaleReason) {
        finishPreparation(RadioState.STALE, stale = reason)
    }

    private fun finishPreparation(state: RadioState, failure: FailureReason? = null, stale: StaleReason? = null) {
        cancel()
        mutableStatus.value = RadioStatus(
            state = state, requestId = registry.newRequest(), failure = failure, stale = stale,
            activeSessionId = session?.id, pagingNeedsRetry = session?.needsRetry ?: false,
            pagingEnded = session?.paginator?.ended ?: false,
        )
    }

    /** Leaving radio mode (or a new local queue replaced it): no more paging and nothing is written to the player afterwards. */
    fun deactivate() {
        cancel()
        session = null
        mutableStatus.value = RadioStatus()
    }

    /** Only a committed session is persisted; in-flight resolution is never resumed after process death. */
    fun exportSession(): PersistedRadio? = session?.paginator?.persistedState()

    /** Restores ready queue ownership without resolving, networking or touching playback. */
    fun restoreSession(saved: PersistedRadio, generation: Long): Boolean {
        val seed = saved.seedCatalogIds.firstOrNull() ?: return false
        if (saved.sessionId.isBlank() || saved.seedCatalogIds.any { it.isBlank() || it.contains("://") }) return false
        deactivate()
        val paginator = RadioPaginator(saved.sessionId, generation, saved.seedCatalogIds.toSet(), config.maxEmptyPages)
        paginator.restoreState(saved)
        session = ActiveSession(saved.sessionId, paginator, generation, sourceFactory(), seed)
        mutableStatus.value = RadioStatus(state = RadioState.ACTIVE, sessionId = saved.sessionId, activeSessionId = saved.sessionId, pagingEnded = saved.ended)
        return true
    }

    private inner class Run(
        val requestId: Long,
    ) {
        val machine = RadioStateMachine()
        var sessionId: String? = null

        fun emit(
            event: RadioEvent,
            candidates: List<ScoredCandidate> = emptyList(),
        ) {
            val t = machine.accept(event)
            if (registry.latestRequestId == requestId) {
                mutableStatus.value = RadioStatus(
                    machine.state, requestId, sessionId, candidates, t.failure, t.stale,
                    pagingNeedsRetry = session?.needsRetry ?: false,
                    pagingEnded = session?.paginator?.ended ?: false,
                    activeSessionId = session?.id,
                )
            }
        }
    }

    private suspend fun run(
        req: RadioStartRequest,
        requestId: Long,
        forceChoice: Boolean,
    ): RadioOutcome {
        val r = Run(requestId)
        r.emit(RadioEvent.Start)
        try {
            val startView = player.view(req.anchor.occurrenceId)
            val ctx =
                RadioRequestContext(
                    requestId, startView.generation, req.anchor.occurrenceId, req.actualPlaybackId,
                    req.asset.revision, req.action, clock(),
                )
            val seed = resolveSeed(req, r, forceChoice)
            r.emit(RadioEvent.Resolved)
            val sessionId = sessionIds()
            r.sessionId = sessionId
            val paginator = RadioPaginator(sessionId, startView.generation, seed.ids, config.maxEmptyPages)
            val requestSource = sourceFactory()
            val first = fetchFirstPage(requestSource, seed.catalogId, paginator, sessionId, startView.generation)
            r.emit(RadioEvent.FirstPageReady(first.isNotEmpty()))
            if (first.isEmpty()) return RadioOutcome.Failed(FailureReason.EMPTY_FIRST_PAGE)
            return commit(req, ctx, sessionId, paginator, first, r, requestSource)
        } catch (e: StepStale) {
            r.emit(RadioEvent.MarkStale(e.reason))
            return RadioOutcome.Stale(e.reason)
        } catch (e: StepFailure) {
            r.emit(RadioEvent.Fail(e.reason))
            return RadioOutcome.Failed(e.reason)
        } catch (e: TimeoutCancellationException) {
            // withTimeout throws a CancellationException subtype; it is a failure only while the caller itself is still active.
            coroutineContext.ensureActive()
            r.emit(RadioEvent.Fail(FailureReason.TIMEOUT))
            return RadioOutcome.Failed(FailureReason.TIMEOUT)
        } catch (e: CancellationException) {
            r.emit(RadioEvent.Cancel)
            throw e
        } catch (e: Exception) {
            // A bug or an unexpected service error must end in FAILED (the player is untouched), not in a crashed job.
            r.emit(RadioEvent.Fail(FailureReason.INTERNAL_ERROR))
            return RadioOutcome.Failed(FailureReason.INTERNAL_ERROR)
        }
    }

    private suspend fun resolveSeed(
        req: RadioStartRequest,
        r: Run,
        forceChoice: Boolean,
    ): ResolvedSeed {
        if (forceChoice) return awaitChoice(req, r, emptyList())
        return when (val res = resolver.resolve(req.asset)) {
            is ResolveResult.Confirmed -> ResolvedSeed(res.catalogId, res.seedCatalogIds)
            is ResolveResult.NeedsChoice -> awaitChoice(req, r, res.candidates)
            is ResolveResult.NotFound -> throw StepFailure(FailureReason.NOT_FOUND)
            ResolveResult.Offline -> throw StepFailure(FailureReason.OFFLINE)
            ResolveResult.PermissionLost -> throw StepFailure(FailureReason.PERMISSION_LOST)
            is ResolveResult.RetryableFailure -> throw StepFailure(FailureReason.RETRYABLE)
        }
    }

    private suspend fun awaitChoice(req: RadioStartRequest, r: Run, candidates: List<ScoredCandidate>): ResolvedSeed {
        val pending = CompletableDeferred<CatalogCandidate?>()
        pendingChoice = pending
        r.emit(RadioEvent.ChoiceRequired, candidates)
        val picked = pending.await()
        pendingChoice = null
        val saved = resolver.recordManualChoice(req.asset, picked) {
            registry.latestRequestId == r.requestId && player.view(req.anchor.occurrenceId).anchorMetadataRevision == req.asset.revision
        }
        if (!saved) throw StepStale(StaleReason.METADATA_CHANGED)
        if (picked == null) throw StepFailure(FailureReason.NOT_FOUND)
        r.emit(RadioEvent.ChoiceMade)
        return ResolvedSeed(picked.catalogId, setOf(picked.catalogId))
    }

    private suspend fun fetchFirstPage(
        source: RadioSourcePort,
        seed: String,
        paginator: RadioPaginator,
        sessionId: String,
        generation: Long,
    ): List<RadioItem> {
        var page = withRetries { withTimeout(config.prepareTimeoutMs) { source.prepare(seed) } }
        var ticket = checkNotNull(paginator.startFetch(sessionId, generation))
        while (true) {
            when (val out = paginator.onPage(ticket, page, sessionId, generation)) {
                is PageOutcome.Append -> {
                    if (out.items.isNotEmpty() || !out.fetchAgain) return out.items
                    // Everything on this page was filtered (seed, repeats, blocked): the service has more, so ask for it.
                    ticket = checkNotNull(paginator.startFetch(sessionId, generation))
                    val continuation = checkNotNull(ticket.continuation)
                    page = withRetries { withTimeout(config.pageTimeoutMs) { source.next(continuation) } }
                }
                PageOutcome.End, PageOutcome.RetryOrEnd, PageOutcome.Stale -> return emptyList()
            }
        }
    }

    /** Retries RETRYABLE source errors with exponential backoff; OFFLINE and exhausted retries become a [StepFailure]. */
    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RadioSourceException) {
                if (e.kind == RadioSourceException.Kind.OFFLINE) throw StepFailure(FailureReason.OFFLINE)
                if (attempt >= config.maxRetries) throw StepFailure(FailureReason.RETRYABLE)
                delay(config.backoffBaseMs shl attempt)
                attempt++
            }
        }
    }

    private suspend fun commit(
        req: RadioStartRequest,
        ctx: RadioRequestContext,
        sessionId: String,
        paginator: RadioPaginator,
        first: List<RadioItem>,
        r: Run,
        requestSource: RadioSourcePort,
    ): RadioOutcome {
        var polls = 0
        while (true) {
            val view = player.view(req.anchor.occurrenceId)
            val snapshot =
                QueueSnapshot(
                    view.generation, view.currentOccurrenceId, view.currentActualPlaybackId, view.anchorMetadataRevision,
                    view.anchorStillExists, registry.latestRequestId, view.blendInProgress,
                )
            when (val check = RadioCommitGuard.check(ctx, snapshot)) {
                is CommitCheck.Stale -> {
                    r.emit(RadioEvent.MarkStale(check.reason))
                    return RadioOutcome.Stale(check.reason)
                }
                CommitCheck.Defer -> {
                    if (++polls > config.maxDeferPolls) {
                        r.emit(RadioEvent.MarkStale(StaleReason.ANCHOR_CHANGED))
                        return RadioOutcome.Stale(StaleReason.ANCHOR_CHANGED)
                    }
                    delay(config.deferPollMs)
                }
                CommitCheck.Proceed -> {
                    val modes = view.modes.copy(shuffle = false)
                    val jobContext = coroutineContext
                    val stillValid = { registry.latestRequestId == ctx.requestId && jobContext[kotlinx.coroutines.Job]?.isActive != false }
                    val committedGeneration =
                        when (req.action) {
                            UserAction.CURRENT -> player.commitCurrent(sessionId, first, modes, ctx, stillValid)
                            UserAction.SELECTED -> player.commitSelected(sessionId, req.anchor, first, modes, ctx, stillValid)
                        }
                    if (committedGeneration == null) {
                        // The player refused because the queue moved after the check: never retry blindly.
                        r.emit(RadioEvent.MarkStale(StaleReason.QUEUE_CHANGED))
                        return RadioOutcome.Stale(StaleReason.QUEUE_CHANGED)
                    }
                    paginator.adoptGeneration(committedGeneration)
                    session = ActiveSession(sessionId, paginator, committedGeneration, requestSource, paginator.persistedState().seedCatalogIds.first())
                    r.emit(RadioEvent.Committed)
                    return RadioOutcome.Active(sessionId, first.size)
                }
            }
        }
    }

    /**
     * Called by the adapter when the queue shrinks, repeat changes, or the user taps retry ([force]). Fetches at most one page
     * plus the chained empty-after-filter pages the paginator allows. Returns the number of appended items, or null when
     * nothing was fetched. Never throws for source problems: it marks the status as needing retry instead of looping.
     */
    suspend fun loadNextPage(force: Boolean = false): Int? {
        val active = session ?: return null
        // One nextPage job per session: a second caller while a fetch runs simply gets null.
        if (!active.pageLock.tryLock()) return null
        try {
            return loadNextPageLocked(active, force)
        } finally {
            active.pageLock.unlock()
        }
    }

    private suspend fun loadNextPageLocked(s: ActiveSession, force: Boolean): Int? {
        if (session !== s || (s.needsRetry && !force)) return null
        val view = player.view("")
        if (session !== s) return null
        if (view.generation != s.generation) {
            // A user edit of the same session: keep paging, but nothing fetched before the edit may be appended.
            s.paginator.adoptGeneration(view.generation)
            s.generation = view.generation
        }
        val repeatOk = RadioPaginationPolicy.mayAutoFetch(view.modes.repeat)
        val wanted =
            RadioPaginationPolicy.shouldFetch(view.remainingAhead, config.lowWater, view.modes.repeat, true, s.paginator.fetchInFlight, s.paginator.ended)
        if (!wanted && !(force && repeatOk && !s.paginator.fetchInFlight && !s.paginator.ended)) return null
        if (force) {
            s.paginator.resetEmptyStreak()
            s.needsRetry = false
        }
        var appended = 0
        while (true) {
            val ticket = s.paginator.startFetch(s.id, s.generation) ?: return appended.takeIf { it > 0 }
            val page =
                try {
                    withRetries { withTimeout(config.pageTimeoutMs) {
                        if (ticket.continuation == null) s.source.prepare(s.seedCatalogId) else s.source.next(ticket.continuation)
                    } }
                } catch (e: StepFailure) {
                    s.paginator.abort(ticket)
                    markPagingRetry(s)
                    return appended.takeIf { it > 0 }
                } catch (e: TimeoutCancellationException) {
                    s.paginator.abort(ticket)
                    coroutineContext.ensureActive()
                    markPagingRetry(s)
                    return appended.takeIf { it > 0 }
                } catch (e: CancellationException) {
                    s.paginator.abort(ticket)
                    throw e
                } catch (e: Exception) {
                    s.paginator.abort(ticket)
                    markPagingRetry(s)
                    return appended.takeIf { it > 0 }
                }
            val nowGeneration = try {
                player.view("").generation
            } catch (e: CancellationException) {
                s.paginator.abort(ticket)
                throw e
            } catch (e: Exception) {
                s.paginator.abort(ticket)
                markPagingRetry(s)
                return appended.takeIf { it > 0 }
            }
            if (session !== s) return appended.takeIf { it > 0 }
            when (val out = s.paginator.onPage(ticket, page, session?.id.orEmpty(), nowGeneration)) {
                is PageOutcome.Append -> {
                    if (out.items.isNotEmpty()) {
                        if (!player.appendTail(s.id, out.items, nowGeneration)) {
                            // The page is not in the queue: forget it so the same continuation is fetched again later.
                            s.paginator.rollbackLastPage()
                            markPagingRetry(s)
                            return appended.takeIf { it > 0 }
                        }
                        appended += out.items.size
                    }
                    if (session === s && mutableStatus.value.activeSessionId == s.id) {
                        mutableStatus.value = mutableStatus.value.copy(pagingNeedsRetry = false, pagingEnded = s.paginator.ended)
                    }
                    if (!out.fetchAgain) return appended
                }
                PageOutcome.Stale -> return appended.takeIf { it > 0 }
                PageOutcome.RetryOrEnd -> {
                    markPagingRetry(s)
                    return appended.takeIf { it > 0 }
                }
                PageOutcome.End -> {
                    if (session === s && mutableStatus.value.activeSessionId == s.id) {
                        mutableStatus.value = mutableStatus.value.copy(pagingEnded = true, pagingNeedsRetry = false)
                    }
                    return appended.takeIf { it > 0 }
                }
            }
        }
    }

    private fun markPagingRetry(s: ActiveSession) {
        s.needsRetry = true
        if (session === s && mutableStatus.value.activeSessionId == s.id) {
            mutableStatus.value = mutableStatus.value.copy(pagingNeedsRetry = true)
        }
    }
}
