/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.radio

/** Domain part of RADIO_FLOW.md: request context, queue generation and the commit guard. No Android or media3 types. */

enum class UserAction { CURRENT, SELECTED }

/** One logical queue entry. [occurrenceId] is a runtime id: the same song twice in a queue has two occurrences. */
data class OccurrenceRef(
    val occurrenceId: String,
    val playlistId: String? = null,
    val revision: Long? = null,
    val entryId: String? = null,
    val localAssetKey: String? = null,
)

/**
 * What happened to the player or queue. Only events that change which future items play (or who owns the queue)
 * advance the generation; a plain seek, pause or technical hydration must not invalidate a request.
 */
enum class QueueEvent(
    val bumpsGeneration: Boolean,
) {
    USER_QUEUE_EDIT(true),
    USER_REORDER(true),
    USER_REMOVE(true),
    NEW_QUEUE(true),
    NEW_RADIO(true),
    SOURCE_CHANGED(true),
    OCCURRENCE_ADVANCED(false),
    SEEK(false),
    PAUSE_RESUME(false),
    HYDRATION(false),
    PREFETCH(false),
}

class QueueGenerationTracker(
    initial: Long = 0,
) {
    var generation: Long = initial
        private set

    fun onEvent(event: QueueEvent): Long {
        if (event.bumpsGeneration) generation++
        return generation
    }
}

/** Only the newest radio request may commit. */
class RadioRequestRegistry {
    private var counter = 0L

    var latestRequestId: Long? = null
        private set

    fun newRequest(): Long {
        counter++
        latestRequestId = counter
        return counter
    }

    fun invalidate() {
        latestRequestId = null
    }
}

data class RadioRequestContext(
    val requestId: Long,
    val queueGeneration: Long,
    val anchorOccurrenceId: String,
    /** The media id actually playing for the anchor (local id or online id): a source change makes the request stale. */
    val anchorActualPlaybackId: String,
    val metadataRevision: String,
    val userAction: UserAction,
    val requestedAt: Long,
)

/** What the player looks like at commit time (read on the player thread). */
data class QueueSnapshot(
    val generation: Long,
    val currentOccurrenceId: String?,
    val currentActualPlaybackId: String?,
    /** Current metadata revision of the anchor's file; null when the file is gone. */
    val anchorMetadataRevision: String?,
    /** For [UserAction.SELECTED]: the chosen occurrence still exists in its playlist context. */
    val anchorStillExists: Boolean,
    val latestRadioRequestId: Long?,
    val blendInProgress: Boolean = false,
)

enum class StaleReason { SUPERSEDED, QUEUE_CHANGED, ANCHOR_CHANGED, SOURCE_CHANGED, METADATA_CHANGED, ANCHOR_REMOVED }

sealed interface CommitCheck {
    data object Proceed : CommitCheck

    /** A blend/promotion is running: postpone and check again; by then the anchor may have moved (then it is Stale). */
    data object Defer : CommitCheck

    data class Stale(
        val reason: StaleReason,
    ) : CommitCheck
}

object RadioCommitGuard {
    fun check(
        ctx: RadioRequestContext,
        now: QueueSnapshot,
    ): CommitCheck {
        if (now.latestRadioRequestId != ctx.requestId) return CommitCheck.Stale(StaleReason.SUPERSEDED)
        when (ctx.userAction) {
            UserAction.CURRENT -> {
                if (now.generation != ctx.queueGeneration) return CommitCheck.Stale(StaleReason.QUEUE_CHANGED)
                if (now.currentOccurrenceId != ctx.anchorOccurrenceId) return CommitCheck.Stale(StaleReason.ANCHOR_CHANGED)
                if (now.currentActualPlaybackId != ctx.anchorActualPlaybackId) return CommitCheck.Stale(StaleReason.SOURCE_CHANGED)
            }
            UserAction.SELECTED -> if (!now.anchorStillExists) return CommitCheck.Stale(StaleReason.ANCHOR_REMOVED)
        }
        if (now.anchorMetadataRevision != ctx.metadataRevision) return CommitCheck.Stale(StaleReason.METADATA_CHANGED)
        // Valid so far; an ongoing blend postpones only a commit that would otherwise be accepted.
        if (ctx.userAction == UserAction.CURRENT && now.blendInProgress) return CommitCheck.Defer
        return CommitCheck.Proceed
    }
}
