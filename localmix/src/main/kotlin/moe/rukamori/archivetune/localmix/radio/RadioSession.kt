/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

enum class RadioState { IDLE, RESOLVING, NEEDS_CHOICE, FETCHING, READY_TO_COMMIT, ACTIVE, CANCELLED, FAILED, STALE }

enum class FailureReason { OFFLINE, RETRYABLE, NOT_FOUND, PERMISSION_LOST, EMPTY_FIRST_PAGE, TIMEOUT, INTERNAL_ERROR }

sealed interface RadioEvent {
    data object Start : RadioEvent

    data object Resolved : RadioEvent

    data object ChoiceRequired : RadioEvent

    data object ChoiceMade : RadioEvent

    /** The first page, after filtering. An empty first page never commits. */
    data class FirstPageReady(
        val nonEmpty: Boolean,
    ) : RadioEvent

    data object Committed : RadioEvent

    /** Dialog closed, user left, job cancelled. Music keeps playing. */
    data object Cancel : RadioEvent

    data class Fail(
        val reason: FailureReason,
    ) : RadioEvent

    data class MarkStale(
        val reason: StaleReason,
    ) : RadioEvent

    /** Leaving radio mode (back to the playlist). */
    data object Exit : RadioEvent
}

data class Transition(
    val from: RadioState,
    val to: RadioState,
    val accepted: Boolean,
    val failure: FailureReason? = null,
    val stale: StaleReason? = null,
)

/**
 * IDLE -> RESOLVING -> NEEDS_CHOICE | FETCHING -> READY_TO_COMMIT -> ACTIVE. RESOLVING, NEEDS_CHOICE, FETCHING and
 * READY_TO_COMMIT may end in CANCELLED, FAILED or STALE, none of which touches the player. A Start from any state except
 * the in-flight ones begins a new attempt; events that do not fit the state are rejected and change nothing.
 */
class RadioStateMachine {
    var state: RadioState = RadioState.IDLE
        private set

    fun accept(event: RadioEvent): Transition {
        val from = state
        val to = next(from, event)
        if (to == null) return Transition(from, from, accepted = false)
        state = to
        return Transition(
            from,
            to,
            accepted = true,
            failure = (event as? RadioEvent.Fail)?.reason ?: if (event is RadioEvent.FirstPageReady && !event.nonEmpty) FailureReason.EMPTY_FIRST_PAGE else null,
            stale = (event as? RadioEvent.MarkStale)?.reason,
        )
    }

    private fun next(
        s: RadioState,
        e: RadioEvent,
    ): RadioState? {
        val inFlight = s == RadioState.RESOLVING || s == RadioState.NEEDS_CHOICE || s == RadioState.FETCHING || s == RadioState.READY_TO_COMMIT
        if (inFlight) {
            when (e) {
                RadioEvent.Cancel -> return RadioState.CANCELLED
                is RadioEvent.Fail -> return RadioState.FAILED
                is RadioEvent.MarkStale -> return RadioState.STALE
                else -> Unit
            }
        }
        return when (s) {
            RadioState.IDLE, RadioState.CANCELLED, RadioState.FAILED, RadioState.STALE, RadioState.ACTIVE ->
                if (e == RadioEvent.Start) RadioState.RESOLVING else if (s == RadioState.ACTIVE && e == RadioEvent.Exit) RadioState.IDLE else null
            RadioState.RESOLVING ->
                when (e) {
                    RadioEvent.Resolved -> RadioState.FETCHING
                    RadioEvent.ChoiceRequired -> RadioState.NEEDS_CHOICE
                    else -> null
                }
            RadioState.NEEDS_CHOICE -> if (e == RadioEvent.ChoiceMade) RadioState.FETCHING else null
            RadioState.FETCHING ->
                if (e is RadioEvent.FirstPageReady) {
                    if (e.nonEmpty) RadioState.READY_TO_COMMIT else RadioState.FAILED
                } else {
                    null
                }
            RadioState.READY_TO_COMMIT -> if (e == RadioEvent.Committed) RadioState.ACTIVE else null
        }
    }
}

enum class RepeatMode { OFF, ONE, ALL }

data class PlayerModes(
    val shuffle: Boolean,
    val repeat: RepeatMode,
)

/** Snapshot of the local queue to come back to. The saved playlist itself is never changed by radio. */
data class ReturnContext(
    val playlistId: String?,
    val revision: Long?,
    val anchorOccurrenceId: String,
    /** Future items after the seed, in their original order. */
    val tail: List<OccurrenceRef>,
    val modes: PlayerModes,
)

data class EnterPlan(
    /** Modes the radio queue should use: explicit order (no shuffle), repeat untouched. */
    val modesToApply: PlayerModes,
    /** False when radio was started from a library screen without a playlist: no guessed "back to playlist". */
    val canOfferReturn: Boolean,
    /** True when a later radio kept the first return context instead of stacking a new one. */
    val keptOriginalReturn: Boolean,
)

data class ReturnPlan(
    /** Playing item first (never interrupted), then the saved tail. */
    val queue: List<OccurrenceRef>,
    val modesToRestore: PlayerModes,
    /** The playlist was re-imported meanwhile: the saved snapshot is restored, the user is told. */
    val playlistRefreshedSince: Boolean,
)

class RadioModeController {
    var returnContext: ReturnContext? = null
        private set

    private var modesBeforeRadio: PlayerModes? = null

    val active: Boolean get() = modesBeforeRadio != null

    /** [context] is the local queue being left; null when the command did not come from a visible playlist. */
    fun enter(
        currentModes: PlayerModes,
        context: ReturnContext?,
    ): EnterPlan {
        val alreadyActive = active
        if (!alreadyActive) {
            modesBeforeRadio = currentModes
            returnContext = context
        }
        // Later radios keep the first context: no undo history grows.
        return EnterPlan(
            modesToApply = PlayerModes(shuffle = false, repeat = currentModes.repeat),
            canOfferReturn = returnContext != null,
            keptOriginalReturn = alreadyActive,
        )
    }

    /** Leaves radio. Returns null when radio was not active or nothing to return to. */
    fun exit(
        playingNow: OccurrenceRef,
        playlistRevisionNow: Long?,
    ): ReturnPlan? {
        val ctx = returnContext
        val modes = modesBeforeRadio
        returnContext = null
        modesBeforeRadio = null
        if (ctx == null || modes == null) return null
        return ReturnPlan(
            queue = listOf(playingNow) + ctx.tail,
            modesToRestore = modes,
            playlistRefreshedSince = ctx.revision != null && playlistRevisionNow != null && playlistRevisionNow != ctx.revision,
        )
    }
}

/** Repeat decides whether the radio may append pages by itself (RADIO_FLOW: automatic pagination only with repeat OFF). */
object RadioPaginationPolicy {
    fun mayAutoFetch(repeat: RepeatMode) = repeat == RepeatMode.OFF

    /** UI shows a hint at the Radio mode when repeating limits appending. */
    fun repeatLimitsAppending(repeat: RepeatMode) = repeat != RepeatMode.OFF

    fun shouldFetch(
        remainingAhead: Int,
        lowWater: Int,
        repeat: RepeatMode,
        sessionActive: Boolean,
        fetchInFlight: Boolean,
        ended: Boolean,
    ): Boolean = sessionActive && !fetchInFlight && !ended && mayAutoFetch(repeat) && remainingAhead <= lowWater

    /** Switching repeat back to OFF may trigger a controlled refill without waiting for the next track change. */
    fun refillOnRepeatChange(
        old: RepeatMode,
        new: RepeatMode,
        remainingAhead: Int,
        lowWater: Int,
    ): Boolean = old != RepeatMode.OFF && new == RepeatMode.OFF && remainingAhead <= lowWater
}
