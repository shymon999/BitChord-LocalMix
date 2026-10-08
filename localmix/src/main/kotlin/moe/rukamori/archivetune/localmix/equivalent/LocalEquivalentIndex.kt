/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix.equivalent

import moe.rukamori.archivetune.localmix.CatalogCandidate
import moe.rukamori.archivetune.localmix.LocalRecording
import moe.rukamori.archivetune.localmix.MatchConfig
import moe.rukamori.archivetune.localmix.ParsedTitle
import moe.rukamori.archivetune.localmix.RecordingMatcher
import moe.rukamori.archivetune.localmix.ScoredCandidate
import moe.rukamori.archivetune.localmix.TextNormalizer
import moe.rukamori.archivetune.localmix.TitleParser
import moe.rukamori.archivetune.localmix.VersionCompat
import kotlin.math.abs

data class LocalTrack(
    val songId: String,
    val recording: LocalRecording,
)

/**
 * Why an accepted match still deserves a second look. Every match [LocalEquivalentIndex] returns passed the automatic
 * criteria; these only mark the ones that passed close to an edge, for the review screen.
 */
enum class MatchDoubt {
    /** Only soft version flags differ (radio edit, remaster). */
    SOFT_VERSION,

    /** Durations differ by more than [LocalEquivalentIndex.CloseDurationMs] (still within the hard cap). */
    DURATION_GAP,

    /** The identity part of the title is not character-identical after folding (punctuation-level difference). */
    TITLE_VARIANT,

    /** The primary artist matches, but the other credited artists differ noticeably. */
    ARTISTS_DIFFER,
}

/** A confident match and the reasons, if any, to double-check it. */
data class EquivalentMatch(
    val track: LocalTrack,
    val doubts: Set<MatchDoubt> = emptySet(),
) {
    val isUncertain: Boolean get() = doubts.isNotEmpty()
}

/**
 * Answers "does the user already have this catalog recording on disk?" for preferLocal playback.
 *
 * Immutable, built once from the whole local library; lookups go through an index keyed by the identity part of the
 * title, so [find] never scans the library. Safe for concurrent reads.
 *
 * Cautious by design: a local track is accepted only when [RecordingMatcher] would choose the catalog song
 * automatically for it (score threshold, exact base title, compatible version, both durations known, primary artist
 * present), the durations differ by at most [MaxDurationDiffMs], and no other, different local recording qualifies too.
 */
class LocalEquivalentIndex(
    tracks: List<LocalTrack>,
    private val config: MatchConfig = MatchConfig(),
) {
    private class Entry(
        val track: LocalTrack,
        val title: ParsedTitle,
        val artistKeys: Set<String>,
        val durationMs: Long?,
    )

    private val matcher = RecordingMatcher(config)
    private val byTitle: Map<String, List<Entry>>

    val size: Int = tracks.size

    init {
        val map = HashMap<String, MutableList<Entry>>()
        for (track in tracks) {
            val title = TitleParser.parse(track.recording.title)
            if (title.baseTitle.isEmpty()) continue
            val keys =
                (TitleParser.splitArtists(track.recording.artists) + title.featured)
                    .map(TextNormalizer::identityKey)
                    .filter { it.isNotEmpty() }
                    .toSet()
            val duration = track.recording.durationMs?.takeIf { it > 0 }
            map.getOrPut(title.baseTitle) { mutableListOf() } += Entry(track, title, keys, duration)
        }
        byTitle = map
    }

    /** Returns the single local track that is confidently the same recording, else null. */
    fun find(candidate: CatalogCandidate): LocalTrack? = findMatch(candidate)?.track

    /** As [find], with the reasons to double-check the answer. */
    fun findMatch(candidate: CatalogCandidate): EquivalentMatch? {
        val catalogSec = candidate.durationSec?.takeIf { it > 0 } ?: return null
        val catalogMs = catalogSec * 1000L
        val key = TitleParser.parse(candidate.title).baseTitle
        val bucket = byTitle[key] ?: return null
        val accepted = bucket.mapNotNull { e -> e.durationMs?.let { scoreIfQualifies(e, candidate, catalogMs) }?.let { e to it } }
        if (accepted.isEmpty()) return null
        val first = accepted.first().first
        if (accepted.any { !sameRecording(first, it.first) }) return null
        val (entry, scored) = accepted.minBy { it.first.track.songId }
        return EquivalentMatch(entry.track, doubtsOf(entry, scored, catalogMs))
    }

    /**
     * Cheap pre-filter for catalog cards that carry no duration (home shelves): true when some local track has the same
     * identity title and one of its artists appears in [artistText] (the card's subtitle, e.g. "Song • Artist").
     * Never a match by itself; it only says that fetching the track's duration is worth a request.
     */
    fun mayHave(
        title: String,
        artistText: String,
    ): Boolean {
        val key = TitleParser.parse(title).baseTitle
        val bucket = byTitle[key] ?: return false
        val text = " " + TextNormalizer.fold(artistText) + " "
        return bucket.any { e -> e.artistKeys.any { k -> k.length >= 2 && text.contains(" $k ") } }
    }

    private fun scoreIfQualifies(
        entry: Entry,
        candidate: CatalogCandidate,
        catalogMs: Long,
    ): ScoredCandidate? {
        val localMs = entry.durationMs ?: return null
        if (abs(localMs - catalogMs) > MaxDurationDiffMs) return null
        val scored = matcher.rank(entry.track.recording, listOf(candidate)).singleOrNull() ?: return null
        return scored.takeIf { isAuto(it) }
    }

    private fun doubtsOf(
        entry: Entry,
        scored: ScoredCandidate,
        catalogMs: Long,
    ): Set<MatchDoubt> {
        val doubts = mutableSetOf<MatchDoubt>()
        if (scored.versionCompat == VersionCompat.SOFT_DIFFERENCE) doubts += MatchDoubt.SOFT_VERSION
        val localMs = entry.durationMs
        // Catalog durations are whole seconds: allow the rounding on top of the close range.
        if (localMs != null && abs(localMs - catalogMs) > CloseDurationMs + 1_000L) doubts += MatchDoubt.DURATION_GAP
        if (scored.breakdown.baseTitle < 1.0) doubts += MatchDoubt.TITLE_VARIANT
        if (scored.breakdown.artist < ArtistsAgreeScore) doubts += MatchDoubt.ARTISTS_DIFFER
        return doubts
    }

    /** The AUTO criteria of [RecordingMatcher], minus the runner-up margin (ambiguity is judged across local files instead). */
    private fun isAuto(s: ScoredCandidate): Boolean {
        val b = s.breakdown
        if (b.duration == null) return false
        if (s.score < config.autoThreshold) return false
        if (s.versionCompat != VersionCompat.SAME && s.versionCompat != VersionCompat.SOFT_DIFFERENCE) return false
        if (!b.titleIdentityVerified || b.baseTitle < config.autoMinBaseTitle) return false
        if (!b.primaryArtistMatches) return false
        return true
    }

    /** Duplicate files of one recording: same title identity, version and artists, durations within [DuplicateDurationMs]. */
    private fun sameRecording(
        a: Entry,
        b: Entry,
    ): Boolean {
        if (a === b) return true
        if (a.title.baseTitle != b.title.baseTitle || a.title.residual != b.title.residual) return false
        if (TitleParser.compare(a.title.version, b.title.version) != VersionCompat.SAME) return false
        if (a.artistKeys != b.artistKeys) return false
        val da = a.durationMs ?: return false
        val db = b.durationMs ?: return false
        return abs(da - db) <= DuplicateDurationMs
    }

    companion object {
        /** Above this difference (plus one second of rounding) a match is flagged [MatchDoubt.DURATION_GAP]. */
        const val CloseDurationMs = 1_000L

        /** Artist score (primary artist matched) under which the other credits differ noticeably. */
        private const val ArtistsAgreeScore = 0.85

        /** Hard cap on top of the matcher score: a good album/artist match must not carry an 8 s different cut. */
        private const val MaxDurationDiffMs = 3_000L
        private const val DuplicateDurationMs = 3_000L
    }
}
