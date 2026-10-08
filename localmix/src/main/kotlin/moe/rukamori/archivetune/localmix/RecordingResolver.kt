/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

enum class LinkConfirmation { AUTO, MANUAL }

/** RADIO_SEED only feeds recommendations; PLAYBACK_EQUIVALENT (future "prefer local") needs stronger evidence and is never set here. */
enum class MatchPurpose { RADIO_SEED, PLAYBACK_EQUIVALENT }

data class CatalogSnapshot(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val durationSec: Int?,
    /** Catalog ids grouped with this result by the recording matcher; manual picks do not establish aliases. */
    val equivalentCatalogIds: List<String> = emptyList(),
)

/**
 * Link between a local file and a catalog recording. [catalogId] == null with [LinkConfirmation.MANUAL] means the user
 * answered "no correct match"; that answer is remembered for the same [recordingRevision].
 */
data class LocalOnlineLink(
    val localAssetKey: String,
    val recordingRevision: String,
    val provider: String,
    val catalogId: String?,
    val confirmation: LinkConfirmation,
    val purpose: MatchPurpose,
    val algorithmVersion: Int,
    val snapshot: CatalogSnapshot?,
    val checkedAt: Long,
) {
    val isNoCorrectMatch: Boolean get() = catalogId == null
}

/** Everything the resolver needs to know about the local file. [assetKey] identifies the locator, not the recording. */
data class LocalAsset(
    val assetKey: String,
    val recording: LocalRecording,
    val fileSizeBytes: Long? = null,
    val modifiedAtMs: Long? = null,
) {
    /** Changes with significant tags, version words, length and file size/mtime; cover art and URI changes do not matter. */
    val revision: String by lazy { RecordingRevision.of(this) }
}

object RecordingRevision {
    fun of(asset: LocalAsset): String {
        val r = asset.recording
        val text =
            listOf(
                TextNormalizer.identityKey(r.title),
                r.artists.joinToString("|") { TextNormalizer.identityKey(it) },
                TextNormalizer.identityKey(r.album.orEmpty()),
                r.durationMs?.div(1000)?.toString().orEmpty(),
                asset.fileSizeBytes?.toString().orEmpty(),
                asset.modifiedAtMs?.toString().orEmpty(),
            ).joinToString("\u001f")
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

/** Search failures the port can report. Anything else (including cancellation) propagates unchanged. */
class CatalogSearchException(
    val kind: Kind,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind { OFFLINE, RETRYABLE }
}

interface CatalogSearchPort {
    /** SONG-filtered search. Throws [CatalogSearchException] on network problems. */
    suspend fun searchSongs(query: String): List<CatalogCandidate>
}

interface LinkStorePort {
    suspend fun find(assetKey: String): LocalOnlineLink?

    suspend fun save(link: LocalOnlineLink)

    /** Atomically writes only if the current value still equals [expected], including an absent initial row. */
    suspend fun compareAndSet(expected: LocalOnlineLink?, link: LocalOnlineLink): Boolean
}

sealed interface ResolveResult {
    data class Confirmed(
        val catalogId: String,
        val link: LocalOnlineLink,
        val source: Source,
    ) : ResolveResult {
        /** Only matcher-confirmed AUTO equivalence may filter additional seed ids from radio pages. */
        val seedCatalogIds: Set<String>
            get() = buildSet {
                add(catalogId)
                if (link.confirmation == LinkConfirmation.AUTO) {
                    link.snapshot?.equivalentCatalogIds?.filter { it.isNotBlank() }?.let(::addAll)
                }
            }
    }

    data class NeedsChoice(
        val candidates: List<ScoredCandidate>,
        val reason: ChoiceReason,
    ) : ResolveResult

    /** [declinedByUser] is true when the user earlier said that no catalog entry is correct for this revision. */
    data class NotFound(
        val declinedByUser: Boolean = false,
        val reason: NotFoundReason? = null,
    ) : ResolveResult

    data object Offline : ResolveResult

    data object PermissionLost : ResolveResult

    data class RetryableFailure(
        val message: String? = null,
    ) : ResolveResult

    enum class Source { MANUAL_LINK, CACHED_AUTO_LINK, AUTO_MATCH, REVALIDATED_MANUAL_LINK }
}

/**
 * Resolution order from IMPLEMENTATION_SPEC section 5: current metadata -> valid link -> SONG search (title + main artist)
 * -> version filter -> grouping -> scoring -> AUTO or NEEDS_CHOICE. At most [maxQueries] searches per call.
 * A failed search never touches the stored link, so a valid manual choice survives an offline phone.
 */
class RecordingResolver(
    private val search: CatalogSearchPort,
    private val store: LinkStorePort,
    private val matcher: RecordingMatcher = RecordingMatcher(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxQueries: Int = 2,
) {
    suspend fun resolve(asset: LocalAsset): ResolveResult {
        val stored = store.find(asset.assetKey)
        val sameRevision = stored != null && stored.recordingRevision == asset.revision
        cachedResult(stored, asset)?.let { return it }
        val candidates = mutableListOf<CatalogCandidate>()
        var decision: MatchDecision? = null
        for (query in queries(asset.recording).take(maxQueries)) {
            try {
                candidates += search.searchSongs(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CatalogSearchException) {
                return when (e.kind) {
                    CatalogSearchException.Kind.OFFLINE -> ResolveResult.Offline
                    CatalogSearchException.Kind.RETRYABLE -> ResolveResult.RetryableFailure(e.message)
                }
            }
            decision = matcher.decide(asset.recording, candidates)
            if (decision is MatchDecision.Auto) break
        }
        // A stale manual link (tags/file changed) is revalidated against fresh results instead of being trusted or dropped.
        if (stored != null && !sameRevision && !stored.isNoCorrectMatch && stored.confirmation == LinkConfirmation.MANUAL) {
            val still = candidates.firstOrNull { it.catalogId == stored.catalogId }
            if (still != null && matcher.decide(asset.recording, listOf(still)) is MatchDecision.Auto) {
                val renewed = stored.copy(recordingRevision = asset.revision, snapshot = still.snapshot(), algorithmVersion = ALGORITHM_VERSION, checkedAt = clock())
                if (!store.compareAndSet(stored, renewed)) return concurrentResult(asset, decision)
                return ResolveResult.Confirmed(still.catalogId, renewed, ResolveResult.Source.REVALIDATED_MANUAL_LINK)
            }
        }
        val staleManual = stored != null && !sameRevision && stored.confirmation == LinkConfirmation.MANUAL && !stored.isNoCorrectMatch
        return when (val d = decision) {
            is MatchDecision.Auto -> if (staleManual) {
                ResolveResult.NeedsChoice(listOfNotNull(d.chosen, d.runnerUp), ChoiceReason.STALE_MANUAL_LINK)
            } else {
                val link = linkFor(asset, d.chosen)
                if (!store.compareAndSet(stored, link)) return concurrentResult(asset, decision)
                ResolveResult.Confirmed(d.chosen.candidate.catalogId, link, ResolveResult.Source.AUTO_MATCH)
            }
            is MatchDecision.NeedsChoice -> ResolveResult.NeedsChoice(d.candidates, if (staleManual) ChoiceReason.STALE_MANUAL_LINK else d.reason)
            is MatchDecision.NotFound -> if (staleManual) ResolveResult.NeedsChoice(emptyList(), ChoiceReason.STALE_MANUAL_LINK) else ResolveResult.NotFound(reason = d.reason)
            null -> ResolveResult.NotFound(reason = NotFoundReason.NO_CANDIDATES)
        }
    }

    /**
     * Stores the user's pick (or "no correct match" for a null [choice]). Refuses to write when [isStillCurrent] says the
     * file changed while the dialog was open; returns false then.
     */
    suspend fun recordManualChoice(
        asset: LocalAsset,
        choice: CatalogCandidate?,
        isStillCurrent: suspend () -> Boolean = { true },
    ): Boolean {
        val expected = store.find(asset.assetKey)
        if (!isStillCurrent()) return false
        return store.compareAndSet(
            expected,
            LocalOnlineLink(
                localAssetKey = asset.assetKey,
                recordingRevision = asset.revision,
                provider = PROVIDER,
                catalogId = choice?.catalogId,
                confirmation = LinkConfirmation.MANUAL,
                purpose = MatchPurpose.RADIO_SEED,
                algorithmVersion = ALGORITHM_VERSION,
                snapshot = choice?.snapshot(),
                checkedAt = clock(),
            ),
        )
    }

    private fun cachedResult(stored: LocalOnlineLink?, asset: LocalAsset): ResolveResult? {
        if (stored == null || stored.recordingRevision != asset.revision || stored.provider != PROVIDER) return null
        if (stored.isNoCorrectMatch) return ResolveResult.NotFound(declinedByUser = true)
        if (stored.confirmation == LinkConfirmation.MANUAL) return ResolveResult.Confirmed(stored.catalogId!!, stored, ResolveResult.Source.MANUAL_LINK)
        if (stored.algorithmVersion == ALGORITHM_VERSION) return ResolveResult.Confirmed(stored.catalogId!!, stored, ResolveResult.Source.CACHED_AUTO_LINK)
        return null
    }

    /** A newer user decision always wins over an older network result; no extra search or overwrite follows. */
    private suspend fun concurrentResult(asset: LocalAsset, decision: MatchDecision?): ResolveResult {
        cachedResult(store.find(asset.assetKey), asset)?.let { return it }
        val choices = when (decision) {
            is MatchDecision.Auto -> listOfNotNull(decision.chosen, decision.runnerUp)
            is MatchDecision.NeedsChoice -> decision.candidates
            else -> emptyList()
        }
        return ResolveResult.NeedsChoice(choices, ChoiceReason.STALE_MANUAL_LINK)
    }

    private fun linkFor(
        asset: LocalAsset,
        chosen: ScoredCandidate,
    ) = LocalOnlineLink(
        asset.assetKey, asset.revision, PROVIDER, chosen.candidate.catalogId, LinkConfirmation.AUTO,
        MatchPurpose.RADIO_SEED, ALGORITHM_VERSION,
        chosen.candidate.snapshot().copy(equivalentCatalogIds = chosen.equivalentIds.distinct()), clock(),
    )

    private fun CatalogCandidate.snapshot() = CatalogSnapshot(title, artists, album, durationSec)

    /** First query is the one the calibration used ("main artist + raw title"); the second adds all credited artists. */
    internal fun queries(r: LocalRecording): List<String> {
        val artists = TitleParser.splitArtists(r.artists)
        val first = (listOfNotNull(mainArtistForQuery(r.artists)) + r.title).joinToString(" ").trim()
        val second = (listOf(r.title) + artists).joinToString(" ").trim()
        return listOf(first, second).filter { it.isNotBlank() }.distinct()
    }

    /** "AC/DC", "Hall & Oates" stay whole; only a list ("A, B" / "A; B") is cut to its first name. */
    private fun mainArtistForQuery(artists: List<String>): String? =
        artists.firstOrNull { it.isNotBlank() }?.split(";", ", ")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val PROVIDER = "YOUTUBE_MUSIC"

        /** Bump when matching rules change so cached AUTO links are re-evaluated (manual links are kept). */
        const val ALGORITHM_VERSION = 2
    }
}
