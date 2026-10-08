/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Second sanity sample: 40 random library files with metadata from the path only (no local duration is available there),
 * candidates from YouTube Music on 2026-10-07. This test only writes a report for manual review; it makes no claim of
 * correctness. Scores here lack the duration term, so the report lists the best version-compatible candidate per sample.
 */
class MediaStoreSanityReportTest {
    private class Sample(
        val id: Int,
        val local: LocalRecording,
        val candidates: List<CatalogCandidate>,
    )

    private fun load(): List<Sample> {
        val text = javaClass.getResourceAsStream("/matching/sanity_nodur.tsv")!!.bufferedReader(Charsets.UTF_8).readText()
        return text.lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }.groupBy { it[0].toInt() }.map { (id, rows) ->
            val f = rows.first()
            Sample(
                id,
                LocalRecording(f[1], listOf(f[2]), f[3].ifEmpty { null }, null),
                rows.map { r -> CatalogCandidate(r[4], r[5], r[6].split('|').filter { it.isNotEmpty() }, r[7].ifEmpty { null }, r[8].toIntOrNull()) },
            )
        }
    }

    @Test fun writesReviewReport() {
        val samples = load()
        assertEquals(40, samples.size)
        val matcher = RecordingMatcher()
        val sb = StringBuilder()
        for (s in samples) {
            sb.append("#${s.id} LOCAL: ${s.local.title} | ${s.local.artists.joinToString()} | ${s.local.album}\n")
            val ranked = matcher.rank(s.local, s.candidates)
            for (c in ranked.take(3)) {
                sb.append("   %.3f %-12s title=%.2f artist=%.2f %s dur=%s '%s' by %s [%s]\n".format(c.score, c.versionCompat, c.breakdown.title, c.breakdown.artist, c.candidate.catalogId, c.candidate.durationSec, c.candidate.title, c.candidate.artists.joinToString("/"), c.candidate.album))
            }
        }
        val dir = File(System.getProperty("localmix.reportDir") ?: "build/reports/localmix").also { it.mkdirs() }
        File(dir, "matching_mediastore_sanity_report.txt").writeText(sb.toString())
    }
}
