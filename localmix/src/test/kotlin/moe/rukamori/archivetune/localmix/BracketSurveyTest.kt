/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Classifies every distinct bracket/suffix text seen in the recorded YouTube Music candidates; report only, for manual review. */
class BracketSurveyTest {
    @Test fun writesClassification() {
        val lines = javaClass.getResourceAsStream("/matching/bracket_texts.txt")!!.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        assertTrue(lines.size > 100)
        val sb = StringBuilder()
        for (t in lines) {
            val v = TitleParser.parseVersionText(t)
            val feat = Regex("^(feat\\.?|ft\\.?|featuring|with)\\s+", RegexOption.IGNORE_CASE).containsMatchIn(t)
            val kind = if (v == null) (if (feat) "FEAT" else "RESIDUAL") else if (v == VersionDescriptor.Plain) "PLAIN" else "${v.flags.joinToString("+")}${v.remixer?.let { " remixer='$it'" } ?: ""}"
            sb.append(kind.padEnd(44)).append(' ').append(t).append('\n')
        }
        val dir = File(System.getProperty("localmix.reportDir") ?: "build/reports/localmix").also { it.mkdirs() }
        File(dir, "bracket_classification.txt").writeText(sb.toString())
    }
}
