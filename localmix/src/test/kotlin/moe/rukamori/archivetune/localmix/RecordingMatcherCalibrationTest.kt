/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the matcher over the 20 real samples (local metadata + 20 YouTube Music SONG candidates each, collected 2026-10-07).
 * Ground truth is a metadata-only judgement (see fixtures/matching_ground_truth_2026-10-07.json), not audio verification.
 */
class RecordingMatcherCalibrationTest {
    private val samples = CalibrationData.load()

    private data class Outcome(
        val sample: CalibrationSample,
        val decision: MatchDecision,
    ) {
        val verdict: Verdict? =
            (decision as? MatchDecision.Auto)?.let { sample.verdictOf(it.chosen.candidate.catalogId) }
        val correctInChoices: Boolean =
            (decision as? MatchDecision.NeedsChoice)?.candidates?.any { c ->
                (listOf(c.candidate.catalogId) + c.equivalentIds).any { sample.verdictOf(it) == Verdict.CORRECT }
            } == true
    }

    private fun run(config: MatchConfig): List<Outcome> {
        val matcher = RecordingMatcher(config)
        return samples.map { s -> Outcome(s, matcher.decide(s.local, s.candidates.map { it.candidate })) }
    }

    @Test
    fun twentyRealSamplesAreLoaded() {
        assertEquals(20, samples.size)
        assertTrue(samples.all { it.correctIds.isNotEmpty() })
    }

    @Test
    fun noAutomaticChoiceIsWrongOrAmbiguousAndMostAreAutomatic() {
        val out = run(MatchConfig())
        writeReport(out)
        val autos = out.filter { it.decision is MatchDecision.Auto }
        val wrong = autos.filter { it.verdict != Verdict.CORRECT }
        assertTrue("wrong AUTO: " + wrong.joinToString { "#${it.sample.id}" }, wrong.isEmpty())
        // every sample has a correct candidate, so none may be NotFound and every NEEDS_CHOICE must offer it
        val notFound = out.filter { it.decision is MatchDecision.NotFound }
        assertTrue("NotFound: " + notFound.joinToString { "#${it.sample.id}" }, notFound.isEmpty())
        val choices = out.filter { it.decision is MatchDecision.NeedsChoice }
        assertTrue("correct candidate missing from choices: " + choices.filter { !it.correctInChoices }.joinToString { "#${it.sample.id}" }, choices.all { it.correctInChoices })
        assertTrue("AUTO ${autos.size}/20", autos.size >= 17)
    }


    /**
     * Safety side of the calibration: remove every correct/ambiguous candidate (the user's recording is not in the
     * catalog results) and require that nothing else is chosen automatically.
     */
    @Test
    fun withoutTheCorrectCandidateNothingIsAutomatic() {
        val matcher = RecordingMatcher()
        val sb = StringBuilder()
        var maxWrongAdmissible = 0.0
        for (s in samples) {
            val wrongOnly = s.candidates.filter { it.verdict == Verdict.WRONG }.map { it.candidate }
            val decision = matcher.decide(s.local, wrongOnly)
            val best = matcher.rank(s.local, wrongOnly).filter { it.versionCompat != VersionCompat.HARD_DIFFERENCE }.maxByOrNull { it.score }
            if (best != null) maxWrongAdmissible = maxOf(maxWrongAdmissible, best.score)
            sb.append("#${s.id} ${decision::class.simpleName} bestAdmissibleWrong=${best?.let { "%.3f %s".format(it.score, it.candidate.title) }}\n")
            assertTrue("#${s.id} chose a wrong candidate automatically: $decision", decision !is MatchDecision.Auto)
        }
        sb.append("max admissible wrong score = %.3f\n".format(maxWrongAdmissible))
        reportFile("matching_missing_correct_report.txt").writeText(sb.toString())
    }

    /** Documents how thresholds trade AUTO count against wrong AUTO; not an assertion on its own. */
    @Test
    fun thresholdSweepReport() {
        val sb = StringBuilder("autoThreshold;autoMargin;AUTO;wrongAuto;choice;notFound\n")
        for (t in listOf(0.80, 0.85, 0.88, 0.90, 0.92, 0.94, 0.96)) {
            for (m in listOf(0.0, 0.05, 0.10, 0.15, 0.20)) {
                val out = run(MatchConfig(autoThreshold = t, autoMargin = m))
                val autos = out.filter { it.decision is MatchDecision.Auto }
                sb.append("$t;$m;${autos.size};${autos.count { it.verdict != Verdict.CORRECT }};${out.count { it.decision is MatchDecision.NeedsChoice }};${out.count { it.decision is MatchDecision.NotFound }}\n")
            }
        }
        reportFile("matching_threshold_sweep.csv").writeText(sb.toString())
    }

    private fun reportFile(name: String): File {
        val dir = File(System.getProperty("localmix.reportDir") ?: "build/reports/localmix").also { it.mkdirs() }
        return File(dir, name)
    }

    private fun writeReport(out: List<Outcome>) {
        val sb = StringBuilder()
        for (o in out) {
            val l = o.sample.local
            sb.append("#${o.sample.id} ${l.title} | ${l.artists.joinToString()} | ${l.durationMs}ms\n")
            when (val d = o.decision) {
                is MatchDecision.Auto ->
                    sb.append("  AUTO ${d.chosen.candidate.catalogId} '${d.chosen.candidate.title}' score=%.3f second=%s verdict=%s\n".format(d.chosen.score, d.runnerUp?.let { "%.3f".format(it.score) } ?: "-", o.verdict))
                is MatchDecision.NeedsChoice -> {
                    sb.append("  CHOICE reason=${d.reason} correctOffered=${o.correctInChoices}\n")
                    d.candidates.forEach { sb.append("    %.3f %s '%s' %s\n".format(it.score, it.candidate.catalogId, it.candidate.title, o.sample.verdictOf(it.candidate.catalogId))) }
                }
                is MatchDecision.NotFound -> sb.append("  NOT_FOUND ${d.reason}\n")
            }
            val top = RecordingMatcher().rank(l, o.sample.candidates.map { it.candidate }).take(4)
            top.forEach { sb.append("    rank %.3f %s %s dur=%s title=%.2f artist=%.2f compat=%s '%s'\n".format(it.score, it.candidate.catalogId, o.sample.verdictOf(it.candidate.catalogId), it.breakdown.duration?.let { x -> "%.2f".format(x) }, it.breakdown.title, it.breakdown.artist, it.versionCompat, it.candidate.title)) }
        }
        reportFile("matching_calibration_report.txt").writeText(sb.toString())
    }
}
