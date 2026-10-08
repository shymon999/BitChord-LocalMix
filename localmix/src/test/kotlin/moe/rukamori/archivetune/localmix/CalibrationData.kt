/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

enum class Verdict { CORRECT, AMBIGUOUS, WRONG }

class CalibrationCandidate(
    val candidate: CatalogCandidate,
    val rank: Int,
    val verdict: Verdict,
)

class CalibrationSample(
    val id: Int,
    val local: LocalRecording,
    val candidates: List<CalibrationCandidate>,
) {
    fun verdictOf(catalogId: String): Verdict = candidates.first { it.candidate.catalogId == catalogId }.verdict

    val correctIds: Set<String> get() = candidates.filter { it.verdict == Verdict.CORRECT }.map { it.candidate.catalogId }.toSet()
}

/** Loads src/test/resources/matching/calibration.tsv (built by fixtures/build_calibration_tsv.py from real YouTube Music candidates). */
object CalibrationData {
    fun load(): List<CalibrationSample> {
        val text =
            CalibrationData::class.java
                .getResourceAsStream("/matching/calibration.tsv")!!
                .bufferedReader(Charsets.UTF_8)
                .readText()
        val rows = text.lines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        return rows.groupBy { it[0].toInt() }.map { (id, group) ->
            val f = group.first()
            CalibrationSample(
                id = id,
                local = LocalRecording(f[1], listOf(f[2]), f[3].ifEmpty { null }, f[4].toLong()),
                candidates =
                    group.map { r ->
                        CalibrationCandidate(
                            CatalogCandidate(r[5], r[6], r[7].split('|').filter { it.isNotEmpty() }, r[8].ifEmpty { null }, r[9].toIntOrNull()),
                            r[10].toInt(),
                            Verdict.valueOf(r[11]),
                        )
                    },
            )
        }.sortedBy { it.id }
    }
}
