/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

/** Version markers. Hard flags make two recordings different; soft flags only weigh on the score. */
enum class VersionFlag(
    val hard: Boolean,
) {
    LIVE(true),
    ACOUSTIC(true),
    INSTRUMENTAL(true),
    ACAPELLA(true),
    KARAOKE(true),
    COVER(true),
    DEMO(true),
    MODIFIED(true), // sped up / slowed / nightcore / reverb / 8D
    EXTENDED(true),
    CLUB_MIX(true),
    DUB(true),
    REMIX(true),
    ALTERNATE(true), // alternate take, stripped, session, orchestral, piano, reprise, rehearsal, outtake
    RADIO_EDIT(false),
    REMASTER(false),
}

data class VersionDescriptor(
    val flags: Set<VersionFlag> = emptySet(),
    /** Folded remixer name, meaningful only with [VersionFlag.REMIX]; null = remixer not stated. */
    val remixer: String? = null,
    /** Identifies distinct variants within a hard flag, such as sped/slowed or piano/orchestral. */
    val hardDetails: Map<VersionFlag, String> = emptyMap(),
) {
    val hardFlags: Set<VersionFlag> get() = flags.filterTo(linkedSetOf()) { it.hard }
    val softFlags: Set<VersionFlag> get() = flags.filterTo(linkedSetOf()) { !it.hard }

    companion object {
        val Plain = VersionDescriptor()
    }
}

enum class VersionCompat {
    /** Same hard flags, compatible remixer, same soft flags. */
    SAME,

    /** Same hard flags; only soft flags (radio edit, remaster) differ. */
    SOFT_DIFFERENCE,

    /** Different hard flags or a different remixer: never an automatic substitute. */
    HARD_DIFFERENCE,

    /** A stated version identity is missing on one side, so compatibility requires a user choice. */
    UNKNOWN,
}

/** A recording title split into its identity part and the version/credit parts. */
data class ParsedTitle(
    val baseTitle: String,
    val version: VersionDescriptor,
    val featured: List<String>,
    /** Folded bracket text that is neither a version nor credits (e.g. "part 2"). */
    val residual: List<String>,
)

object TitleParser {
    private val Brackets = Regex("[(\\[]([^()\\[\\]]*)[)\\]]")
    private val FeatPrefix = Regex("^(feat\\.?|ft\\.?|featuring|with)\\s+", RegexOption.IGNORE_CASE)
    private val TrailingFeat = Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val NoisePrefix = Regex("^from\\b", RegexOption.IGNORE_CASE)
    private val ArtistSplit =
        Regex(
            ",|;|/|\\s+&\\s+|\\s+\\+\\s+|\\s+x\\s+|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+vs\\.?\\s+",
            RegexOption.IGNORE_CASE,
        )
    /** YouTube auto-generated artist channels are named "<Artist> - Topic". */
    private val TopicSuffix = Regex("\\s+-\\s+topic$", RegexOption.IGNORE_CASE)
    private val RemixKeywords = setOf("remix", "rework", "bootleg", "refix", "flip", "recut", "redux", "reedit")
    private val EndingWords = setOf("mix", "edit", "version", "cut", "remix")
    private val CutWords = setOf("radio", "extended", "club", "dub", "edit", "mix", "version", "cut", "remix", "vip")
    private val GenericWords =
        setOf(
            "original", "radio", "extended", "club", "edit", "version", "album", "single", "new", "official", "vocal",
            "dub", "the", "full", "short", "long", "clean", "explicit", "us", "uk", "mix", "remix", "cut", "mono", "stereo", "re", "video",
            "main", "lp", "2nd", "3rd", "first", "second",
        )
    private val IgnorableWords =
        setOf("original", "album", "single", "explicit", "clean", "deluxe", "bonus", "version", "mix", "edit", "mono", "stereo", "digital", "us", "uk", "main", "lp", "vocal", "full")
    private val NonRemixFlags =
        setOf(
            VersionFlag.LIVE, VersionFlag.ACOUSTIC, VersionFlag.INSTRUMENTAL, VersionFlag.ACAPELLA, VersionFlag.KARAOKE,
            VersionFlag.COVER, VersionFlag.DEMO, VersionFlag.MODIFIED, VersionFlag.REMASTER, VersionFlag.ALTERNATE,
        )

    /** Splits "A, B & C" credits. "&" and "+" separate names, so "Florence + The Machine" splits the same way on both sides. */
    fun splitArtists(raw: List<String>): List<String> =
        raw.flatMap { it.split(ArtistSplit) }.map { it.replace(TopicSuffix, "").trim() }.filter { it.isNotEmpty() }

    private val LiveFollowers = setOf("at", "in", "from", "on", "version", "recording", "performance", "session", "sessions", "demo", "edit", "mix", "acoustic")

    /** "Live", "Live at Wembley", "Acoustic Live"; not a subtitle that merely contains the word ("A Life We Can Live"). */
    private fun isLive(tokens: List<String>): Boolean {
        if (tokens.first() == "live") return tokens.size == 1 || tokens[1] in LiveFollowers || tokens[1].all(Char::isDigit)
        return tokens.last() == "live" && tokens.size <= 3
    }

    /** Interprets one bracket/suffix text; null when it carries no version information. */
    fun parseVersionText(text: String): VersionDescriptor? {
        val tokens = TextNormalizer.foldDroppingPossessive(text).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val flags = linkedSetOf<VersionFlag>()

        fun has(vararg w: String) = w.any { it in tokens }
        if (isLive(tokens)) flags += VersionFlag.LIVE
        if (has("acoustic", "unplugged")) flags += VersionFlag.ACOUSTIC
        if (has("instrumental")) flags += VersionFlag.INSTRUMENTAL
        if (has("acapella", "acappella") || ("a" in tokens && "cappella" in tokens)) flags += VersionFlag.ACAPELLA
        if (has("karaoke")) flags += VersionFlag.KARAOKE
        if (has("cover", "tribute", "originally", "orginally", "style") || ("made" in tokens && has("famous", "popular"))) flags += VersionFlag.COVER
        if (has("demo", "demonstration")) flags += VersionFlag.DEMO
        if (has("sped", "slowed", "nightcore", "reverb", "8d")) flags += VersionFlag.MODIFIED
        if (has("alternate", "alternative", "stripped", "session", "sessions", "orchestral", "piano", "reprise", "rehearsal", "outtake")) {
            flags += VersionFlag.ALTERNATE
        }
        if (has("extended")) flags += VersionFlag.EXTENDED
        if (has("club") && has("mix", "version", "edit")) flags += VersionFlag.CLUB_MIX
        if (has("dub")) flags += VersionFlag.DUB
        if (has("radio") && has("edit", "version", "mix", "cut")) flags += VersionFlag.RADIO_EDIT
        if (has("remaster", "remastered")) flags += VersionFlag.REMASTER

        var remixer: String? = null
        if (tokens.any { it in RemixKeywords } || flags.none { it in NonRemixFlags }) {
            val keyword = tokens.indexOfFirst { it in RemixKeywords }
            val cut = tokens.indexOfFirst { it in CutWords }
            val endsLikeMix = tokens.last() in EndingWords
            val vip = tokens.indexOf("vip")
            val versionWords = setOf("live", "acoustic", "unplugged", "instrumental", "acapella", "acappella", "karaoke", "cover", "tribute", "demo", "demonstration", "sped", "slowed", "nightcore", "reverb", "8d", "alternate", "alternative", "stripped", "session", "sessions", "orchestral", "piano", "reprise", "rehearsal", "outtake", "remaster", "remastered")
            fun names(range: List<String>) = range.filter { it !in GenericWords && it !in versionWords && !it.all(Char::isDigit) }
            if (keyword >= 0 || (endsLikeMix && cut > 0)) {
                // "Flip Fantasia": a keyword in first place that is not "remix" names the remixer by what follows it.
                val leading = keyword == 0 && tokens[0] != "remix" && tokens.size > 1
                val name = if (leading) names(tokens.drop(1)) else names(tokens.take(if (keyword >= 0) keyword else cut))
                if (keyword >= 0 || name.isNotEmpty()) {
                    flags += VersionFlag.REMIX
                    if (name.isNotEmpty()) remixer = name.joinToString(" ")
                }
            } else if (vip >= 0) {
                flags += VersionFlag.REMIX
                names(tokens.take(vip)).takeIf { it.isNotEmpty() }?.let { remixer = it.joinToString(" ") }
            }
            // "Video Edit", "Single Version", "Short Edit": a shortened cut of the same recording, like a radio edit.
            if (VersionFlag.REMIX !in flags && flags.none { it.hard } && (has("edit", "cut") || (has("short", "single") && has("version", "mix")))) {
                flags += VersionFlag.RADIO_EDIT
            }
        }
        if (flags.isEmpty()) {
            return if (tokens.all { it in IgnorableWords || it.all(Char::isDigit) }) VersionDescriptor.Plain else null
        }
        val details = linkedMapOf<VersionFlag, String>()
        if (VersionFlag.MODIFIED in flags) {
            details[VersionFlag.MODIFIED] = tokens.filter { it in setOf("sped", "slowed", "nightcore", "reverb", "8d") }.distinct().sorted().joinToString(" ")
        }
        if (VersionFlag.ALTERNATE in flags) {
            val alternateWords = setOf("alternate", "alternative", "stripped", "session", "sessions", "orchestral", "piano", "reprise", "rehearsal", "outtake", "take")
            details[VersionFlag.ALTERNATE] = tokens.filter { it in alternateWords || it.all(Char::isDigit) }.map { when (it) { "alternative" -> "alternate"; "sessions" -> "session"; else -> it } }.joinToString(" ")
        }
        if (VersionFlag.LIVE in flags) {
            tokens.filter { it != "live" && it !in LiveFollowers && it !in GenericWords }.joinToString(" ").takeIf { it.isNotEmpty() }?.let { details[VersionFlag.LIVE] = it }
        }
        return VersionDescriptor(flags, remixer, details)
    }

    fun parse(rawTitle: String): ParsedTitle {
        val groups = mutableListOf<String>()
        var rest =
            Brackets.replace(rawTitle) { m ->
                groups += m.groupValues[1]
                " "
            }
        // " - Radio Edit" / " - Axwell Mix" / " - feat. X" suffixes, but not "Part 1" style subtitles.
        val dash = rest.lastIndexOf(" - ")
        if (dash > 0) {
            val seg = rest.substring(dash + 3)
            if (FeatPrefix.containsMatchIn(seg) || parseVersionText(seg) != null) {
                groups += seg
                rest = rest.substring(0, dash)
            }
        }
        val flags = linkedSetOf<VersionFlag>()
        val remixers = linkedSetOf<String>()
        val featured = mutableListOf<String>()
        val hardDetails = linkedMapOf<VersionFlag, String>()
        TrailingFeat.find(rest)?.let { m ->
            featured += splitArtists(listOf(m.groupValues[2]))
            rest = rest.substring(0, m.range.first)
        }
        val residual = mutableListOf<String>()
        for (g in groups) {
            val text = g.trim()
            if (text.isEmpty()) continue
            if (FeatPrefix.containsMatchIn(text)) {
                featured += splitArtists(listOf(text.replace(FeatPrefix, "")))
                continue
            }
            if (NoisePrefix.containsMatchIn(text)) continue
            for (part in text.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
                val v = parseVersionText(part)
                if (v == null) {
                    TextNormalizer.fold(part).takeIf { it.isNotEmpty() }?.let { residual += it }
                    continue
                }
                flags += v.flags
                for ((flag, detail) in v.hardDetails) {
                    val previous = hardDetails[flag]
                    hardDetails[flag] = when {
                        previous == null || previous == detail -> detail
                        flag == VersionFlag.MODIFIED -> (previous.split(' ') + detail.split(' ')).toSortedSet().joinToString(" ")
                        else -> listOf(previous, detail).sorted().joinToString(" | ")
                    }
                }
                v.remixer?.let { remixers += it }
            }
        }
        return ParsedTitle(TextNormalizer.fold(rest).ifEmpty { rest.trim().lowercase(java.util.Locale.ROOT) }, VersionDescriptor(flags, remixers.singleOrNull(), hardDetails), featured, residual)
    }

    fun compare(
        local: VersionDescriptor,
        candidate: VersionDescriptor,
    ): VersionCompat {
        if (local.hardFlags != candidate.hardFlags) return VersionCompat.HARD_DIFFERENCE
        var unknown = false
        for (flag in local.hardDetails.keys + candidate.hardDetails.keys) {
            val a = local.hardDetails[flag]
            val b = candidate.hardDetails[flag]
            if (a == null || b == null) unknown = true
            else if (a != b) return VersionCompat.HARD_DIFFERENCE
        }
        if (VersionFlag.REMIX in local.flags) {
            val a = local.remixer
            val b = candidate.remixer
            if (a != null && b != null && !remixerEqual(a, b)) return VersionCompat.HARD_DIFFERENCE
            if (a == null || b == null) unknown = true
        }
        if (unknown) return VersionCompat.UNKNOWN
        return if (local.softFlags == candidate.softFlags) VersionCompat.SAME else VersionCompat.SOFT_DIFFERENCE
    }

    /** Named remixers must agree exactly after folding; a token subset can identify a different artist. */
    internal fun remixerEqual(
        a: String,
        b: String,
    ): Boolean {
        return a == b
    }
}
