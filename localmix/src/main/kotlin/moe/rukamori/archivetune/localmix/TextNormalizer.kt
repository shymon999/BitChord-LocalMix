/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.text.Normalizer
import java.util.Locale

/** Text folding shared by the recording matcher. Pure JVM, no Android dependencies. */
object TextNormalizer {
    private val PossessiveS = Regex("['’ʼ]s\\b")
    private val Apostrophes = Regex("['’ʼ`´]")
    private val NonAlnum = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** Lowercase, diacritics removed, "&" and "+" read as separators, punctuation dropped, spaces collapsed. */
    fun fold(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFKD)
            .let(::stripLatinMarks)
            .lowercase(Locale.ROOT)
            .replace("ł", "l")
            .replace("ø", "o")
            .replace("đ", "d")
            .replace("ß", "ss")
            .replace("æ", "ae")
            .replace("œ", "oe")
            .replace(Apostrophes, "")
            .replace(NonAlnum, " ")
            .trim()

    /**
     * Removes combining marks only after Latin, Greek and Cyrillic letters ("e" + acute -> "e"). After other scripts the marks
     * carry meaning (Japanese dakuten: "か" and "が" differ, Indic vowel signs) and are kept.
     */
    private fun stripLatinMarks(decomposed: String): String {
        val out = StringBuilder(decomposed.length)
        var strip = true
        decomposed.codePoints().forEach { cp ->
            when (Character.getType(cp).toByte()) {
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> if (!strip) out.appendCodePoint(cp)
                else -> {
                    out.appendCodePoint(cp)
                    strip =
                        when (Character.UnicodeScript.of(cp)) {
                            Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC,
                            Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED,
                            -> true
                            else -> false
                        }
                }
            }
        }
        return out.toString()
    }

    /** Same as [fold], but "'s" is dropped first ("Axwell's Mix" -> "axwell mix"). */
    fun foldDroppingPossessive(text: String): String = fold(text.replace(PossessiveS, ""))

    fun tokens(text: String): List<String> = fold(text).split(' ').filter { it.isNotEmpty() }

    /** Preserves the identity of symbol-only names when folding has no letters or digits left. */
    fun identityKey(text: String): String = fold(text).ifEmpty { text.trim().lowercase(Locale.ROOT) }

    /** 1.0 for equal non-empty strings, otherwise 1 - editDistance / maxLength on the folded text. */
    fun similarity(
        a: String,
        b: String,
    ): Double {
        val x = tokens(a).joinToString(" ")
        val y = tokens(b).joinToString(" ")
        // Symbol-only names ("!!!", "+/-") fold to nothing: equal raw text is still a perfect match.
        if (x.isEmpty() || y.isEmpty()) {
            val same = x.isEmpty() && y.isEmpty() && a.isNotBlank() && a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)
            return if (same) 1.0 else 0.0
        }
        if (x == y) return 1.0
        return 1.0 - editDistance(x, y).toDouble() / maxOf(x.length, y.length)
    }

    internal fun editDistance(
        a: String,
        b: String,
    ): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
