/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import kotlin.math.abs

/** Metadata of a local recording. Duration is in milliseconds; null when unknown (EXTINF:-1 is NOT a duration). */
data class LocalRecording(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
)

/** A catalog (YouTube Music SONG) result. Catalog duration is whole seconds, rounded by the service. */
data class CatalogCandidate(
    val catalogId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSec: Int? = null,
)

/**
 * Thresholds. Defaults come from the calibration on fixtures/matching_ground_truth_2026-10-07.json
 * (see RecordingMatcherCalibrationTest and PROGRESS.md), not from guesses.
 */
data class MatchConfig(
    val titleWeight: Double = 0.40,
    val artistWeight: Double = 0.35,
    val durationWeight: Double = 0.20,
    val albumWeight: Double = 0.05,
    /** Catalog durations are whole seconds; the first second of difference is rounding, not evidence. */
    val durationRoundingMs: Long = 1_000,
    /** Difference (after rounding allowance) up to which the duration counts as fully matching. */
    val durationFullMs: Long = 2_000,
    /** Difference (after rounding allowance) at which the duration score reaches zero. */
    val durationZeroMs: Long = 10_000,
    /** Minimal total score of the best group for AUTO. */
    val autoThreshold: Double = 0.85,
    /** Minimal lead of the best group over the next group for AUTO. */
    val autoMargin: Double = 0.10,
    /** Groups below this score are not offered in the choice list. */
    val showThreshold: Double = 0.55,
    val maxChoices: Int = 5,
    /** AUTO needs the identity part of the title to be (almost) identical: only punctuation, spacing and case may differ. */
    val autoMinBaseTitle: Double = 0.97,
    /** Missing duration never counts as agreement; by default it also forbids AUTO. */
    val allowAutoWithoutDuration: Boolean = false,
    /** A candidate whose artists do not include the local primary artist is never AUTO. */
    val requirePrimaryArtistForAuto: Boolean = true,
)

data class ScoreBreakdown(
    val title: Double,
    val artist: Double,
    /** null = duration unknown on either side (contributes 0, not agreement). */
    val duration: Double?,
    /** null = album unknown on either side (neutral half credit). */
    val album: Double?,
    val primaryArtistMatches: Boolean,
    /** Similarity of the identity part of the title, before the bracket penalty. */
    val baseTitle: Double = title,
    val titleIdentityVerified: Boolean = true,
)

data class ScoredCandidate(
    val candidate: CatalogCandidate,
    val score: Double,
    val breakdown: ScoreBreakdown,
    val versionCompat: VersionCompat,
    /** Other catalog ids judged to be the same recording as [candidate] (equivalent releases). */
    val equivalentIds: List<String> = emptyList(),
)

enum class ChoiceReason {
    /** Best group is not strong enough. */
    WEAK_SCORE,

    /** Two different recordings score almost the same. */
    CLOSE_COMPETITOR,

    /** Catalog or local duration missing. */
    DURATION_UNKNOWN,

    /** Primary artist is not among the candidate artists. */
    ARTIST_MISMATCH,

    /** The title differs by more than punctuation, so a different song with a similar title cannot be excluded. */
    TITLE_NOT_EXACT,

    /** Remix or detailed version information is missing and cannot establish agreement. */
    VERSION_UNVERIFIED,

    /** A manual link exists but the file changed and fresh results did not confirm it; the choice is not overwritten silently. */
    STALE_MANUAL_LINK,
}

enum class NotFoundReason {
    NO_CANDIDATES,

    /** Every plausible candidate was a different version (live/remix/cover/...). */
    ONLY_DIFFERENT_VERSIONS,
    NO_SIMILAR_CANDIDATE,
}

sealed interface MatchDecision {
    data class Auto(
        val chosen: ScoredCandidate,
        val runnerUp: ScoredCandidate?,
    ) : MatchDecision

    data class NeedsChoice(
        val candidates: List<ScoredCandidate>,
        val reason: ChoiceReason,
    ) : MatchDecision

    data class NotFound(
        val reason: NotFoundReason,
    ) : MatchDecision
}

class RecordingMatcher(
    private val config: MatchConfig = MatchConfig(),
) {
    private val numberedSubtitle = Regex("\\b(part|pt|chapter|movement|vol|volume)\\s+[0-9ivx]+\\b")
    private val guestCredit = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)

    private class Parsed(
        val title: ParsedTitle,
        val artists: List<String>,
        val artistKeys: Set<String>,
        val primary: Set<String>,
        val album: String?,
        val durationMs: Long?,
    )

    fun decide(
        local: LocalRecording,
        candidates: List<CatalogCandidate>,
    ): MatchDecision {
        if (candidates.isEmpty()) return MatchDecision.NotFound(NotFoundReason.NO_CANDIDATES)
        val l = parseLocal(local)
        val scored = rank(l, candidates)
        val admissible = scored.filter { it.versionCompat != VersionCompat.HARD_DIFFERENCE }
        val shown = admissible.filter { it.score >= config.showThreshold }
        if (shown.isEmpty()) {
            val similarButDifferent =
                scored.any { it.versionCompat == VersionCompat.HARD_DIFFERENCE && it.score >= config.showThreshold }
            return MatchDecision.NotFound(
                if (similarButDifferent) NotFoundReason.ONLY_DIFFERENT_VERSIONS else NotFoundReason.NO_SIMILAR_CANDIDATE,
            )
        }
        val groups = groupEquivalent(shown, l)
        val best = groups.first()
        val second = groups.getOrNull(1)
        val reason = autoBlocker(best, second, local.durationMs)
        if (reason == null) return MatchDecision.Auto(best, second)
        return MatchDecision.NeedsChoice(groups.take(config.maxChoices), reason)
    }

    /** Scores every candidate (hard-different versions included, flagged), best first. Exposed for calibration. */
    fun rank(
        local: LocalRecording,
        candidates: List<CatalogCandidate>,
    ): List<ScoredCandidate> = rank(parseLocal(local), candidates)

    private fun rank(
        l: Parsed,
        candidates: List<CatalogCandidate>,
    ): List<ScoredCandidate> =
        candidates
            .distinctBy { it.catalogId }
            .map { score(l, it) }
            .sortedByDescending { it.score }

    private fun autoBlocker(
        best: ScoredCandidate,
        second: ScoredCandidate?,
        localDurationMs: Long?,
    ): ChoiceReason? {
        if (!config.allowAutoWithoutDuration && (best.breakdown.duration == null || localDurationMs == null)) {
            return ChoiceReason.DURATION_UNKNOWN
        }
        if (best.score < config.autoThreshold) return ChoiceReason.WEAK_SCORE
        if (best.versionCompat == VersionCompat.UNKNOWN) return ChoiceReason.VERSION_UNVERIFIED
        if (!best.breakdown.titleIdentityVerified || best.breakdown.baseTitle < config.autoMinBaseTitle) return ChoiceReason.TITLE_NOT_EXACT
        if (config.requirePrimaryArtistForAuto && !best.breakdown.primaryArtistMatches) return ChoiceReason.ARTIST_MISMATCH
        if (second != null && best.score - second.score < config.autoMargin) return ChoiceReason.CLOSE_COMPETITOR
        return null
    }

    private fun parseLocal(local: LocalRecording): Parsed = parse(local.title, local.artists, local.album, local.durationMs)

    private fun parse(
        title: String,
        artists: List<String>,
        album: String?,
        durationMs: Long?,
    ): Parsed {
        val t = TitleParser.parse(title)
        val names = (TitleParser.splitArtists(artists) + t.featured).distinctBy { artistKey(it) }
        val keys = names.map(::artistKey).filter { it.isNotEmpty() }.toSet()
        // Keep the whole first credit group: AC/DC or Hall & Oates must not match AC or Hall alone.
        val primaryCredit = artists.firstOrNull { it.isNotBlank() }?.substringBefore(',')?.substringBefore(';')?.split(guestCredit)?.firstOrNull()
        val primary = primaryCredit?.let { TitleParser.splitArtists(listOf(it)).map(::artistKey).filter { it.isNotEmpty() }.toSet() }.orEmpty()
        return Parsed(t, names, keys, primary, album, durationMs)
    }

    private fun score(
        l: Parsed,
        c: CatalogCandidate,
    ): ScoredCandidate {
        val p = parse(c.title, c.artists, c.album, c.durationSec?.let { it * 1000L })
        val compat = TitleParser.compare(l.title.version, p.title.version)
        val baseTitleSimilarity = TextNormalizer.similarity(l.title.baseTitle, p.title.baseTitle)
        var titleScore = baseTitleSimilarity
        // Unrecognised bracket text ("part 2") that differs, or exists on the catalog side only, hints at another recording.
        if (l.title.residual != p.title.residual) {
            titleScore *= if (l.title.residual.isNotEmpty() && p.title.residual.isNotEmpty()) 0.9 else 0.95
        }
        val primaryHit = l.primary.isNotEmpty() && l.primary.all { name -> p.artistKeys.any { sameName(it, name) } }
        val artistScore = artistScore(l, p, primaryHit)
        val durationScore = durationScore(l.durationMs, p.durationMs)
        val albumScore = albumScore(l.album, c.album)
        val total =
            config.titleWeight * titleScore +
                config.artistWeight * artistScore +
                config.durationWeight * (durationScore ?: 0.0) +
                config.albumWeight * (albumScore ?: 0.5)
        var adjusted = total / (config.titleWeight + config.artistWeight + config.durationWeight + config.albumWeight)
        if (compat == VersionCompat.SOFT_DIFFERENCE) adjusted -= 0.02
        val residualConflict = l.title.residual != p.title.residual && (
            (l.title.residual.isNotEmpty() && p.title.residual.isNotEmpty()) ||
                (l.title.residual + p.title.residual).any { numberedSubtitle.containsMatchIn(it) }
            )
        return ScoredCandidate(c, adjusted.coerceIn(0.0, 1.0), ScoreBreakdown(titleScore, artistScore, durationScore, albumScore, primaryHit, baseTitleSimilarity, !residualConflict), compat)
    }

    /** Symbol-only names ("+/-", "!!!") fold to nothing; keep them comparable by their raw text. */
    private fun artistKey(name: String): String = TextNormalizer.identityKey(name)

    private fun sameName(
        a: String,
        b: String,
    ): Boolean = a == b || TextNormalizer.similarity(a, b) >= 0.88

    private fun artistScore(
        l: Parsed,
        c: Parsed,
        primaryHit: Boolean,
    ): Double {
        if (l.artistKeys.isEmpty() || c.artistKeys.isEmpty()) return 0.0
        val common = l.artistKeys.count { a -> c.artistKeys.any { sameName(a, it) } }
        if (common == 0) return 0.0
        val union = l.artistKeys.size + c.artistKeys.size - common
        val jaccard = common.toDouble() / union
        val containment = common.toDouble() / minOf(l.artistKeys.size, c.artistKeys.size)
        // The service often lists only some of the credited artists, so a partial list is not a mismatch.
        return if (primaryHit) 0.7 + 0.3 * jaccard else 0.35 + 0.35 * containment
    }

    private fun durationScore(
        localMs: Long?,
        catalogMs: Long?,
    ): Double? {
        if (localMs == null || localMs <= 0 || catalogMs == null || catalogMs <= 0) return null
        val diff = maxOf(0L, abs(localMs - catalogMs) - config.durationRoundingMs)
        if (diff <= config.durationFullMs - config.durationRoundingMs) return 1.0
        if (diff >= config.durationZeroMs) return 0.0
        val start = config.durationFullMs - config.durationRoundingMs
        return 1.0 - (diff - start).toDouble() / (config.durationZeroMs - start)
    }

    private fun albumScore(
        local: String?,
        catalog: String?,
    ): Double? {
        val a = local?.let { albumKey(it) }.orEmpty()
        val b = catalog?.let { albumKey(it) }.orEmpty()
        if (a.isEmpty() || b.isEmpty()) return null
        return if (a == b) 1.0 else if (a.contains(b) || b.contains(a)) 0.8 else TextNormalizer.similarity(a, b).let { if (it >= 0.85) it else 0.0 }
    }

    private fun albumKey(album: String): String = TitleParser.parse(album).baseTitle

    /** Groups candidates that are the same recording on different releases; the input must be sorted best first. */
    private fun groupEquivalent(
        sorted: List<ScoredCandidate>,
        l: Parsed,
    ): List<ScoredCandidate> {
        val heads = mutableListOf<ScoredCandidate>()
        val members = mutableMapOf<String, MutableList<String>>()
        for (c in sorted) {
            val head = heads.firstOrNull { sameRecording(it.candidate, c.candidate, l) }
            if (head == null) {
                heads += c
                members[c.candidate.catalogId] = mutableListOf()
            } else {
                members.getValue(head.candidate.catalogId) += c.candidate.catalogId
            }
        }
        return heads.map { it.copy(equivalentIds = members.getValue(it.candidate.catalogId)) }
    }

    private fun sameRecording(
        a: CatalogCandidate,
        b: CatalogCandidate,
        l: Parsed,
    ): Boolean {
        val x = parse(a.title, a.artists, a.album, a.durationSec?.let { it * 1000L })
        val y = parse(b.title, b.artists, b.album, b.durationSec?.let { it * 1000L })
        if (x.title.baseTitle != y.title.baseTitle) return false
        if (x.title.residual != y.title.residual) return false
        if (TitleParser.compare(x.title.version, y.title.version) != VersionCompat.SAME) return false
        val sharedArtist = x.artistKeys.any { k -> y.artistKeys.any { sameName(k, it) } }
        // Two releases that each list a different subset of the local artists are still one recording.
        val bothTouchLocal = listOf(x, y).all { s -> s.artistKeys.any { k -> l.artistKeys.any { sameName(k, it) } } }
        if (!sharedArtist && !bothTouchLocal) return false
        val dx = x.durationMs
        val dy = y.durationMs
        return dx != null && dy != null && abs(dx - dy) <= 3_000
    }
}
