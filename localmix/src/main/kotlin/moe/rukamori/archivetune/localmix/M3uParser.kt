/*
 * ArchiveTune (2026)
 * © Rukamori - github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmix

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

enum class M3uLocationKind {
    LOCAL_PATH,
    FILE_URI,
    CONTENT_URI,
    REMOTE_URI,
    UNSUPPORTED_URI,
}

data class M3uEntry(
    val ordinal: Int,
    val lineNumber: Int,
    val location: String,
    val kind: M3uLocationKind,
    val title: String? = null,
    val durationSeconds: Double? = null,
)

data class M3uDiagnostic(
    val lineNumber: Int,
    val reason: Reason,
) {
    enum class Reason {
        INVALID_DURATION,
        ORPHAN_EXTINF,
        HLS_PLAYLIST,
        NON_LOCAL_ENTRY,
    }
}

data class ParsedM3u(
    val name: String?,
    val entries: List<M3uEntry>,
    val diagnostics: List<M3uDiagnostic>,
    val isHls: Boolean,
)

/** Parses only; ownership of the supplied stream/reader stays with the caller. */
object M3uParser {
    private val UriScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val WindowsDrive = Regex("^[a-zA-Z]:[\\\\/]")

    fun parse(input: InputStream): ParsedM3u =
        parse(
            InputStreamReader(
                input,
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT),
            ),
        )

    fun parse(reader: Reader): ParsedM3u {
        var name: String? = null
        var pendingTitle: String? = null
        var pendingDuration: Double? = null
        var pendingLine: Int? = null
        var isHls = false
        val entries = mutableListOf<M3uEntry>()
        val diagnostics = mutableListOf<M3uDiagnostic>()

        reader.buffered().lineSequence().forEachIndexed { index, raw ->
            val lineNumber = index + 1
            val line = (if (index == 0) raw.removePrefix("\uFEFF") else raw).trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-", ignoreCase = true) -> {
                    if (!isHls) diagnostics += M3uDiagnostic(lineNumber, M3uDiagnostic.Reason.HLS_PLAYLIST)
                    isHls = true
                }
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> {
                    name = line.substringAfter(':').trim().takeIf(String::isNotEmpty)
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    pendingLine?.let { diagnostics += M3uDiagnostic(it, M3uDiagnostic.Reason.ORPHAN_EXTINF) }
                    pendingLine = lineNumber
                    val value = line.substringAfter(':')
                    val duration = value.substringBefore(',').trim().toDoubleOrNull()
                    if (duration == null || !duration.isFinite() || (duration < 0 && duration != -1.0)) {
                        diagnostics += M3uDiagnostic(lineNumber, M3uDiagnostic.Reason.INVALID_DURATION)
                    }
                    pendingDuration = duration?.takeIf { it.isFinite() && it >= 0 }
                    pendingTitle = value.substringAfter(',', "").trim().takeIf(String::isNotEmpty)
                }
                line.startsWith('#') -> Unit
                else -> {
                    val kind = locationKind(line)
                    entries += M3uEntry(entries.size, lineNumber, line, kind, pendingTitle, pendingDuration)
                    if (kind == M3uLocationKind.REMOTE_URI || kind == M3uLocationKind.UNSUPPORTED_URI) {
                        diagnostics += M3uDiagnostic(lineNumber, M3uDiagnostic.Reason.NON_LOCAL_ENTRY)
                    }
                    pendingTitle = null
                    pendingDuration = null
                    pendingLine = null
                }
            }
        }
        pendingLine?.let { diagnostics += M3uDiagnostic(it, M3uDiagnostic.Reason.ORPHAN_EXTINF) }
        return ParsedM3u(name, entries, diagnostics, isHls)
    }

    private fun locationKind(location: String): M3uLocationKind {
        if (WindowsDrive.containsMatchIn(location) || !UriScheme.containsMatchIn(location)) {
            return M3uLocationKind.LOCAL_PATH
        }
        return when (location.substringBefore(':').lowercase(java.util.Locale.ROOT)) {
            "file" -> M3uLocationKind.FILE_URI
            "content" -> M3uLocationKind.CONTENT_URI
            "http", "https" -> M3uLocationKind.REMOTE_URI
            else -> M3uLocationKind.UNSUPPORTED_URI
        }
    }
}
